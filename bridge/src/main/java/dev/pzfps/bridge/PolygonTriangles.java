package dev.pzfps.bridge;

import java.util.ArrayList;
import java.util.List;

/** Worker-local triangulation of a simple source contour; indices retain source UV correspondence. */
final class PolygonTriangles {
    private PolygonTriangles() {}

    static int[] triangulate(List<float[]> points) {
        // Installed B42 maximum is 79; bound pathological mod contours explicitly.
        if (points.size() < 3 || points.size() > 256)
            throw new IllegalArgumentException("Polygon requires 3..256 points");
        double scale = 1;
        for (float[] p : points) {
            if (p.length < 2 || !Float.isFinite(p[0]) || !Float.isFinite(p[1]))
                throw new IllegalArgumentException("Non-finite polygon point");
            scale = Math.max(scale, Math.max(Math.abs(p[0]), Math.abs(p[1])));
        }
        double epsilon = scale * scale * 1e-10;
        var ring = new ArrayList<Integer>();
        for (int i = 0; i < points.size(); i++) {
            if (ring.isEmpty() || !same(points.get(ring.getLast()), points.get(i))) ring.add(i);
        }
        if (ring.size() > 1 && same(points.get(ring.getFirst()), points.get(ring.getLast())))
            ring.removeLast();
        // Remove straight intermediate corners, not reversals or arbitrary close features.
        boolean changed = true;
        while (changed && ring.size() > 3) {
            changed = false;
            for (int i = 0; i < ring.size(); i++) {
                float[] a = points.get(ring.get((i + ring.size()-1) % ring.size()));
                float[] b = points.get(ring.get(i));
                float[] c = points.get(ring.get((i+1) % ring.size()));
                if (Math.abs(cross(a,b,c)) <= epsilon
                        && (b[0]-a[0])*(double)(b[0]-c[0]) + (b[1]-a[1])*(double)(b[1]-c[1]) <= 0) {
                    ring.remove(i); changed = true; break;
                }
            }
        }
        double area = 0;
        for (int i=0; i<ring.size(); i++) {
            float[] a=points.get(ring.get(i)), b=points.get(ring.get((i+1)%ring.size()));
            area += a[0]*(double)b[1] - a[1]*(double)b[0];
        }
        if (ring.size()<3 || Math.abs(area)<=epsilon)
            throw new IllegalArgumentException("Degenerate polygon");
        double sign = Math.signum(area);
        for (int i=0; i<ring.size(); i++) for (int j=i+1; j<ring.size(); j++) {
            if (j==i+1 || (i==0 && j==ring.size()-1)) continue;
            float[] a=points.get(ring.get(i)), b=points.get(ring.get((i+1)%ring.size()));
            float[] c=points.get(ring.get(j)), d=points.get(ring.get((j+1)%ring.size()));
            double abC=cross(a,b,c), abD=cross(a,b,d), cdA=cross(c,d,a), cdB=cross(c,d,b);
            if ((abC>epsilon && abD < -epsilon || abC < -epsilon && abD>epsilon)
                    && (cdA>epsilon && cdB < -epsilon || cdA < -epsilon && cdB>epsilon))
                throw new IllegalArgumentException("Self-intersecting polygon");
        }
        var triangles = new ArrayList<Integer>();
        while (ring.size()>3) {
            boolean found = false;
            for (int i=0; i<ring.size(); i++) {
                int a=ring.get((i+ring.size()-1)%ring.size()), b=ring.get(i), c=ring.get((i+1)%ring.size());
                if (sign*cross(points.get(a),points.get(b),points.get(c))<=epsilon) continue;
                boolean occupied = false;
                for (int p : ring) {
                    if (p==a || p==b || p==c) continue;
                    if (sign*cross(points.get(a),points.get(b),points.get(p))>=-epsilon
                            && sign*cross(points.get(b),points.get(c),points.get(p))>=-epsilon
                            && sign*cross(points.get(c),points.get(a),points.get(p))>=-epsilon) {
                        occupied=true; break;
                    }
                }
                if (occupied) continue;
                triangles.add(a); triangles.add(b); triangles.add(c);
                ring.remove(i); found=true; break;
            }
            if (!found) throw new IllegalArgumentException("Non-simple or degenerate polygon contour");
        }
        if (sign*cross(points.get(ring.get(0)),points.get(ring.get(1)),points.get(ring.get(2)))<=epsilon)
            throw new IllegalArgumentException("Degenerate polygon remainder");
        triangles.addAll(ring);
        return triangles.stream().mapToInt(Integer::intValue).toArray();
    }

    private static boolean same(float[] a, float[] b) { return a[0]==b[0] && a[1]==b[1]; }
    static double cross(float[] a, float[] b, float[] c) {
        return (b[0]-(double)a[0])*(c[1]-(double)a[1]) - (b[1]-(double)a[1])*(c[0]-(double)a[0]);
    }
}
