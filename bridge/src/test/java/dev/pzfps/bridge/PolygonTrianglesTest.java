package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PolygonTrianglesTest {
    @TempDir Path temporary;
    private static List<float[]> points(float... coords) {
        var result = new ArrayList<float[]>();
        for (int i=0;i<coords.length;i+=2) result.add(new float[]{coords[i],coords[i+1]});
        return result;
    }

    private static void verifyArea(List<float[]> points, int[] triangles) {
        double polygon=0, emitted=0;
        for (int i=0;i<points.size();i++) {
            var a=points.get(i); var b=points.get((i+1)%points.size());
            polygon += a[0]*(double)b[1]-a[1]*(double)b[0];
        }
        for (int i=0;i<triangles.length;i+=3) {
            double area=PolygonTriangles.cross(points.get(triangles[i]),points.get(triangles[i+1]),points.get(triangles[i+2]));
            assertTrue(area*polygon>0,"Triangle winding must follow contour, without overdraw");
            emitted+=area;
        }
        assertEquals(polygon,emitted,Math.max(1,Math.abs(polygon))*1e-7);
    }

    @Test void concaveCutoutSurvivesBothWindingsAndEveryStartCorner() {
        var shape=points(0,0, 3,0, 3,3, 2,3, 2,1, 1,1, 1,3, 0,3);
        for (int direction=0;direction<2;direction++) {
            Collections.reverse(shape);
            for (int start=0;start<shape.size();start++) {
                Collections.rotate(shape,1);
                int[] triangles=PolygonTriangles.triangulate(shape);
                assertEquals(18,triangles.length);
                verifyArea(shape,triangles);
                for (int i=0;i<triangles.length;i+=3) {
                    var a=shape.get(triangles[i]); var b=shape.get(triangles[i+1]); var c=shape.get(triangles[i+2]);
                    double x=(a[0]+b[0]+c[0])/3.0, y=(a[1]+b[1]+c[1])/3.0;
                    assertFalse(x>1 && x<2 && y>1,"No triangle may occupy the opening");
                }
            }
        }
    }

    @Test void duplicateClosureAndCollinearLeadingCornersAreHandled() {
        var shape=points(0,0, 1,0, 2,0, 2,2, 2,2, 0,2, 0,0);
        var triangles=PolygonTriangles.triangulate(shape);
        assertEquals(6,triangles.length);
        verifyArea(shape,triangles);
    }

    @Test void invalidContoursFailExplicitlyInsteadOfInventingAFan() {
        assertThrows(IllegalArgumentException.class,()->PolygonTriangles.triangulate(points(0,0,1,1,2,2)));
        assertThrows(IllegalArgumentException.class,()->PolygonTriangles.triangulate(points(0,0,2,2,0,2,2,0)));
        assertThrows(IllegalArgumentException.class,()->PolygonTriangles.triangulate(points(0,0,1,0,Float.NaN,1)));
    }

    @Test void liveMeshUsesConcaveTrianglesAndPreservesSourceCoordinates() throws Exception {
        Path path=temporary.resolve("polygon.json");
        Files.writeString(path,"""
                {"schema_version":1,"tiles":{"test_notch":{"geometry":[
                {"kind":"polygon","points":[[0,0],[3,0],[3,3],[2,3],[2,1],[1,1],[1,3],[0,3]]}]}}}
                """);
        var object=new WorldState.TileObject(0,"IsoObject","normal","test_notch",
                false,false,false,false,false,false,false);
        var square=new WorldState.Square(0,0,0,-1,0,255,255,255,
                false,true,false,false,false,false,List.of(object));
        var builder=new WorldMeshBuilder(TileGeometryRegistry.load(path));
        var chunk=new WorldState.Chunk(0,0,1,1,List.of(square));
        var vertices=builder.build(chunk).texturedBatches().getFirst().vertices();
        int stride=WorldMeshBuilder.TEXTURED_FLOATS_PER_VERTEX;
        assertEquals(18*stride,vertices.length);
        double area=0;
        for(int i=0;i<vertices.length;i+=3*stride) {
            float[] a={vertices[i],vertices[i+1]}, b={vertices[i+stride],vertices[i+stride+1]},
                    c={vertices[i+2*stride],vertices[i+2*stride+1]};
            double cross=PolygonTriangles.cross(a,b,c);
            assertTrue(cross>0); area+=cross;
        }
        assertEquals(14*WorldMeshBuilder.AUTHORED_HEIGHT_TO_WORLD,area,1e-5);
        for(int i=0;i<vertices.length;i+=stride) {
            float x=vertices[i]-.5f, y=vertices[i+1]/WorldMeshBuilder.AUTHORED_HEIGHT_TO_WORLD;
            assertEquals(64+64*x,vertices[i+9],.001);
            assertEquals(224+32*x-78.38367f*y,vertices[i+10],.001);
        }
        assertArrayEquals(vertices,builder.build(chunk).texturedBatches().getFirst().vertices());
    }

    /** Opt-in installed-data audit, never copies proprietary contours into the repository. */
    @Test void installedPolygonsWhenExplicitlyProvided() throws Exception {
        String path=System.getenv("PZFPS_GEOMETRY_AUDIT");
        org.junit.jupiter.api.Assumptions.assumeTrue(path!=null,"Installed-data audit requires PZFPS_GEOMETRY_AUDIT");
        var tiles=new JSONObject(Files.readString(Path.of(path))).getJSONObject("tiles");
        int count=0, overlappingFans=0;
        for (String sprite: tiles.keySet()) {
            var geometry=tiles.getJSONObject(sprite).optJSONArray("geometry");
            if (geometry==null) continue;
            for (int i=0;i<geometry.length();i++) {
                var value=geometry.getJSONObject(i);
                if (!value.getString("kind").equals("polygon")) continue;
                var source=value.getJSONArray("points");
                var shape=new ArrayList<float[]>();
                for (int p=0;p<source.length();p++) shape.add(new float[]{source.getJSONArray(p).getFloat(0),source.getJSONArray(p).getFloat(1)});
                try { verifyArea(shape,PolygonTriangles.triangulate(shape)); }
                catch (RuntimeException failure) { throw new AssertionError(sprite+" primitive "+i,failure); }
                double signedFan=0, absoluteFan=0;
                for (int t=1;t<shape.size()-1;t++) {
                    double area=PolygonTriangles.cross(shape.get(0),shape.get(t),shape.get(t+1));
                    signedFan+=area; absoluteFan+=Math.abs(area);
                }
                if (absoluteFan-Math.abs(signedFan)>1e-7) {
                    overlappingFans++;
                    System.out.println("Overlapping old fan: "+sprite+" primitive="+i);
                }
                count++;
            }
        }
        assertTrue(count>0);
        System.out.println("Installed polygons validated="+count+" oldFansWithOverlap="+overlappingFans);
    }
}
