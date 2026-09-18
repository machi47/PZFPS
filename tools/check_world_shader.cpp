// Compile the actual embedded renderer shaders and exercise light-only updates,
// cutouts and perspective depth ordering in an offscreen macOS OpenGL context.
// Near (<=16m eye depth) layer errors and physical-occlusion errors fail the test.
// Distant precision residuals are reported, not silently counted as fixed.
// No game instance, simulation or asset needed.
// Build: clang++ -std=c++17 -Wno-deprecated-declarations -framework OpenGL
//        tools/check_world_shader.cpp -o .local/build/check_world_shader
#include <OpenGL/OpenGL.h>
#include <OpenGL/gl.h>
#include <OpenGL/glext.h>
#include <array>
#include <cmath>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

static std::string embedded(const std::string& source, const std::string& name) {
    const std::string marker = "String " + name + " = \"\"\"";
    auto start = source.find(marker);
    if (start == std::string::npos) throw std::runtime_error("Missing shader " + name);
    start += marker.size();
    auto end = source.find("\"\"\"", start);
    if (end == std::string::npos) throw std::runtime_error("Unterminated shader " + name);
    return source.substr(start, end - start);
}

static GLuint compile(GLenum kind, const std::string& source) {
    GLuint shader = glCreateShader(kind);
    const char* text = source.c_str();
    glShaderSource(shader, 1, &text, nullptr);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[8192] = {};
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        throw std::runtime_error(log);
    }
    return shader;
}

int main(int argc, char** argv) {
    CGLContextObj context = nullptr;
    try {
        if (argc != 2) throw std::runtime_error("Pass InProcessWorldRenderer.java");
        std::ifstream input(argv[1]);
        if (!input) throw std::runtime_error("Cannot read renderer source");
        std::string source((std::istreambuf_iterator<char>(input)), {});
        CGLPixelFormatAttribute attrs[] = {kCGLPFAAccelerated, kCGLPFAAllowOfflineRenderers,
                static_cast<CGLPixelFormatAttribute>(0)};
        CGLPixelFormatObj format = nullptr;
        GLint count = 0;
        if (CGLChoosePixelFormat(attrs, &format, &count) != kCGLNoError || !format)
            throw std::runtime_error("No accelerated pixel format");
        CGLError result = CGLCreateContext(format, nullptr, &context);
        CGLDestroyPixelFormat(format);
        if (result != kCGLNoError || CGLSetCurrentContext(context) != kCGLNoError)
            throw std::runtime_error("Cannot create offscreen context");
        GLuint program = glCreateProgram();
        GLuint vertex = compile(GL_VERTEX_SHADER, embedded(source, "vertex"));
        GLuint fragment = compile(GL_FRAGMENT_SHADER, embedded(source, "fragment"));
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        const char* attributes[] = {"inPosition", "inNormal", "inColor", "inSourcePixel", "inLayer", "inLightingIndex"};
        for (GLuint i = 0; i < 6; i++) glBindAttribLocation(program, i, attributes[i]);
        glLinkProgram(program);
        GLint linked = 0;
        glGetProgramiv(program, GL_LINK_STATUS, &linked);
        if (!linked) {
            char log[8192] = {};
            glGetProgramInfoLog(program, sizeof(log), nullptr, log);
            throw std::runtime_error(log);
        }
        glUseProgram(program);
        auto uniform = [&](const char* name) {
            GLint location = glGetUniformLocation(program, name);
            if (location < 0) throw std::runtime_error(std::string("Missing uniform ") + name);
            return location;
        };
        const GLfloat identity[] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
        glUniformMatrix4fv(uniform("uMvp"), 1, GL_FALSE, identity);
        glUniform3f(uniform("uOrigin"), 0, 0, 0);
        glUniform1i(uniform("uTextured"), 0);
        glUniform1i(uniform("uMaterial"), 1);
        glUniform1i(uniform("uTexture"), 0);
        glUniform1i(uniform("uLighting"), 1);
        glUniform1i(uniform("uLightingEnabled"), 1);
        // Optional solely so the same regression can reproduce the old shader failure.
        glUniform1f(glGetUniformLocation(program, "uDepthUnit"), 1.f / 16777215.f);
        GLuint output = 0, framebuffer = 0, light = 0;
        glGenTextures(1, &output);
        glBindTexture(GL_TEXTURE_2D, output);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 16, 16, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glGenFramebuffersEXT(1, &framebuffer);
        glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, framebuffer);
        glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT, GL_COLOR_ATTACHMENT0_EXT, GL_TEXTURE_2D, output, 0);
        if (glCheckFramebufferStatusEXT(GL_FRAMEBUFFER_EXT) != GL_FRAMEBUFFER_COMPLETE_EXT)
            throw std::runtime_error("Incomplete offscreen framebuffer");
        GLuint white = 0;
        glGenTextures(1, &white);
        glBindTexture(GL_TEXTURE_2D, white);
        const unsigned char whitePixel[] = {255, 255, 255, 255};
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, whitePixel);
        glActiveTexture(GL_TEXTURE1);
        glGenTextures(1, &light);
        glBindTexture(GL_TEXTURE_2D, light);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        std::vector<unsigned char> pixels(8 * 512 * 4, 255);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 8, 512, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
        const GLfloat positions[] = {-1,-1,0, 3,-1,0, -1,3,0};
        GLuint vbo = 0;
        glGenBuffers(1, &vbo);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, sizeof(positions), positions, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 0, nullptr);
        glVertexAttrib3f(1, 0, 1, 0);
        glVertexAttrib3f(2, 1, 1, 1);
        glVertexAttrib1f(5, 0);
        glViewport(0, 0, 16, 16);
        auto draw = [&]() {
            glClearColor(0.2f, 0.3f, 0.4f, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            std::array<unsigned char, 4> pixel{};
            glReadPixels(8, 8, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel.data());
            return pixel[0];
        };
        int bright = draw();
        for (size_t i = 0; i < pixels.size(); i += 4) pixels[i] = pixels[i + 1] = pixels[i + 2] = 128;
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 8, 512, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
        int dim = draw();
        glUniform1i(uniform("uTextured"), 1);
        glUniform1i(uniform("uMaterial"), 0);
        glUniform4f(uniform("uCrop"), 0, 0, 1, 1);
        glUniform4f(uniform("uUvBounds"), 0, 0, 1, 1);
        glUniform1i(uniform("uSurfaceKind"), 0);
        glVertexAttrib2f(3, 0.5, 0.5);
        int sourceDim = draw();
        // Raster-trimmed solid crate versus real alpha opening: only the verified
        // closed family may repair alpha. The same texture remains a hole otherwise.
        glActiveTexture(GL_TEXTURE0);
        std::array<unsigned char, 36> trimmed{};
        for (int channel = 0; channel < 4; channel++) trimmed[16 + channel] = 255;
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 3, 3, 0, GL_RGBA, GL_UNSIGNED_BYTE, trimmed.data());
        glUniform4f(uniform("uCrop"), 0, 0, 3, 3);
        glUniform4f(uniform("uProjectedBounds"), 0, 0, 1, 1);
        glVertexAttrib2f(3, 0, 0);
        int ordinaryHole = draw();
        glUniform1i(uniform("uSurfaceKind"), 3);
        int repairedCrate = draw();
        GLenum error = glGetError();
        if (error != GL_NO_ERROR || bright < 240 || dim < 120 || dim > 135 || sourceDim != 128
                || ordinaryHole != 51 || repairedCrate != 128)
            throw std::runtime_error("Light-only render failed: bright=" + std::to_string(bright)
                    + " dim=" + std::to_string(dim) + " GL=" + std::to_string(error));
        std::cout << "renderer=" << glGetString(GL_RENDERER) << "\nversion=" << glGetString(GL_VERSION)
                  << "\nshaderCompileLink=passed\nlightOnlyUpdate=passed bright=" << bright
                  << " dim=" << dim << " sourceDim=" << sourceDim << " geometryUploads=1 lightUploads=2\n";
        std::cout << "closedCrateEdge=passed ordinaryHole=" << ordinaryHole
                  << " repairedCrate=" << repairedCrate << '\n';
        // Native PZ atlases have whole-page mipmaps. A transparent sprite region
        // must stay transparent even beside opaque art at fractional mip boundaries.
        // This tests the production sampler, not a separately reimplemented formula.
        GLuint atlas = 0, sourceVbo = 0;
        glGenTextures(1,&atlas);
        glBindTexture(GL_TEXTURE_2D,atlas);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glGenBuffers(1,&sourceVbo);
        glBindBuffer(GL_ARRAY_BUFFER,sourceVbo);
        const float sourceCoordinates[] = {0,0,32,0,0,32};
        glBufferData(GL_ARRAY_BUFFER,sizeof(sourceCoordinates),sourceCoordinates,GL_STATIC_DRAW);
        glEnableVertexAttribArray(3);
        glVertexAttribPointer(3,2,GL_FLOAT,GL_FALSE,0,nullptr);
        glUniform1i(uniform("uSurfaceKind"),0);
        glUniform1i(uniform("uLightingEnabled"),0);
        glUniform4f(uniform("uCrop"),0,0,16,16);
        int atlasLeakPixels = 0;
        for (int offset : {17,21,27}) {
            std::vector<unsigned char> atlasPixels(64*64*4,255);
            for (int y=offset;y<offset+16;y++) for(int x=offset;x<offset+16;x++)
                for(int c=0;c<4;c++) atlasPixels[(y*64+x)*4+c]=0;
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,64,64,0,GL_RGBA,GL_UNSIGNED_BYTE,atlasPixels.data());
            glGenerateMipmapEXT(GL_TEXTURE_2D);
            glUniform4f(uniform("uUvBounds"),offset/64.f,offset/64.f,(offset+16)/64.f,(offset+16)/64.f);
            for (int viewport : {4,8,16}) {
                glViewport(0,0,viewport,viewport);
                glClear(GL_COLOR_BUFFER_BIT);
                glDrawArrays(GL_TRIANGLES,0,3);
                std::vector<unsigned char> result(viewport*viewport*4);
                glReadPixels(0,0,viewport,viewport,GL_RGBA,GL_UNSIGNED_BYTE,result.data());
                for (int i=0;i<viewport*viewport;i++) if(result[4*i]!=51) atlasLeakPixels++;
            }
        }
        std::cout << "atlasMipIsolation cases=9 leakedPixels=" << atlasLeakPixels << '\n';
        if (atlasLeakPixels) throw std::runtime_error("Atlas mip samples leak unrelated artwork");
        glDisableVertexAttribArray(3);
        glBindBuffer(GL_ARRAY_BUFFER,vbo);
        glDeleteBuffers(1,&sourceVbo);
        glBindTexture(GL_TEXTURE_2D,white);
        glDeleteTextures(1,&atlas);
        glUniform4f(uniform("uUvBounds"),0,0,1,1);
        // Coplanar wall and attachment use different tessellations. Layer two
        // must win locally, independent of angle and submission order. Quantify
        // the far-range limitation of keeping the total depth displacement <=8mm.
        constexpr int size = 128;
        glBindTexture(GL_TEXTURE_2D, output);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glBindTexture(GL_TEXTURE_2D, white);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, whitePixel);
        GLuint depth = 0;
        glGenRenderbuffersEXT(1, &depth);
        glBindRenderbufferEXT(GL_RENDERBUFFER_EXT, depth);
        glRenderbufferStorageEXT(GL_RENDERBUFFER_EXT, GL_DEPTH_COMPONENT24, size, size);
        glFramebufferRenderbufferEXT(GL_FRAMEBUFFER_EXT, GL_DEPTH_ATTACHMENT_EXT, GL_RENDERBUFFER_EXT, depth);
        if (glCheckFramebufferStatusEXT(GL_FRAMEBUFFER_EXT) != GL_FRAMEBUFFER_COMPLETE_EXT)
            throw std::runtime_error("Incomplete depth framebuffer");
        glViewport(0, 0, size, size);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glUniform1i(uniform("uLightingEnabled"), 0);
        glUniform1i(uniform("uSurfaceKind"), 0);
        glUniform4f(uniform("uCrop"), 0, 0, 1, 1);
        glVertexAttrib2f(3, .5f, .5f);
        constexpr float near = .035f, far = 400;
        const GLfloat perspective[] = {1,0,0,0, 0,1,0,0,
            0,0,-(far+near)/(far-near),-1, 0,0,-2*far*near/(far-near),0};
        glUniformMatrix4fv(uniform("uMvp"), 1, GL_FALSE, perspective);
        // Opaque wire coverage in front of an opaque wall, in BOTH draw orders.
        // Edge alpha must never blend with the clear colour and then block the wall.
        int fenceFailures = 0;
        for (int alpha : {0, 25, 100, 155, 230, 255}) {
            for (bool reverse : {false, true}) {
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                auto surface = [&](bool fence) {
                    float d = fence ? 2.f : 3.f;
                    const float vertices[] = {-d,-d,-d, 3*d,-d,-d, -d,3*d,-d};
                    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STREAM_DRAW);
                    const unsigned char texel[] = {255,255,255,static_cast<unsigned char>(fence ? alpha : 255)};
                    glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,1,1,0,GL_RGBA,GL_UNSIGNED_BYTE,texel);
                    glUniform1i(uniform("uSurfaceKind"),fence ? 4 : 0);
                    glVertexAttrib1f(4,0);
                    glVertexAttrib3f(2,fence ? 1 : 0,fence ? 0 : 1,0);
                    glDisable(GL_BLEND);
                    glDrawArrays(GL_TRIANGLES,0,3);
                };
                if (reverse) { surface(false); surface(true); }
                else { surface(true); surface(false); }
                std::array<unsigned char,4> pixel{};
                glReadPixels(64,64,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel.data());
                bool wire = alpha >= 128;
                if (pixel[wire ? 0 : 1] < 240 || pixel[wire ? 1 : 0] > 10) fenceFailures++;
            }
        }
        std::cout << "fenceCoverage cases=12 failures=" << fenceFailures << '\n';
        if (fenceFailures) throw std::runtime_error("Fence coverage/depth ordering failed");
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,1,1,0,GL_RGBA,GL_UNSIGNED_BYTE,whitePixel);
        glUniform1i(uniform("uSurfaceKind"),0);
        int failures = 0, cases = 0, distantWrong = 0, nearWrong = 0;
        for (float distance : {.5f, 2.f, 8.f, 32.f}) {
            for (float slope : {-.85f, -.4f, 0.f, .4f, .85f}) {
                for (bool reverse : {false, true}) {
                    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                    auto surface = [&](int subdivisions, int layer) {
                        std::vector<float> vertices;
                        auto point = [&](float x, float y) {
                            float w = distance / (1 + slope * x);
                            vertices.insert(vertices.end(), {x*w, y*w, -w});
                        };
                        for (int y=0; y<subdivisions; y++) for (int x=0; x<subdivisions; x++) {
                            float a=-1+2.f*x/subdivisions, b=-1+2.f*y/subdivisions;
                            float c=-1+2.f*(x+1)/subdivisions, d=-1+2.f*(y+1)/subdivisions;
                            point(a,b); point(c,b); point(c,d);
                            point(a,b); point(c,d); point(a,d);
                        }
                        glBufferData(GL_ARRAY_BUFFER, vertices.size()*sizeof(float), vertices.data(), GL_STREAM_DRAW);
                        glVertexAttrib1f(4, layer);
                        glVertexAttrib3f(2, layer==1 ? 1 : 0, layer==2 ? 1 : 0, 0);
                        glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(vertices.size()/3));
                    };
                    if (reverse) { surface(8,2); surface(1,1); }
                    else { surface(1,1); surface(8,2); }
                    std::vector<unsigned char> result(size*size*4);
                    glReadPixels(0,0,size,size,GL_RGBA,GL_UNSIGNED_BYTE,result.data());
                    int wrong = 0;
                    for (int y=2; y<size-2; y++) for (int x=2; x<size-2; x++) {
                        size_t pixel=4*(y*size+x);
                        if (result[pixel+1] < 240 || result[pixel] > 10) {
                            wrong++;
                            float eyeDepth = distance / (1 + slope * (-1 + 2.f*(x+.5f)/size));
                            if (eyeDepth <= 16) nearWrong++;
                            else distantWrong++;
                        }
                    }
                    cases++;
                    if (wrong) {
                        failures++;
                        std::cout << "coplanarFailure distance=" << distance << " slope=" << slope
                                  << " reverse=" << reverse << " pixels=" << wrong << '\n';
                    }
                }
            }
        }
        std::cout << "coplanarLayers cases=" << cases << " casesWithWrongPixels=" << failures
                  << " wrongPixelsWithin16m=" << nearWrong << " wrongPixelsBeyond16m=" << distantWrong << '\n';
        if (nearWrong) throw std::runtime_error("Near coplanar layer ordering is unstable");
        // The precision floor must not grow into a large physical displacement.
        // A higher-priority object 2cm behind an actual wall must stay hidden
        // locally (10cm at 100m, where 2cm is below this projection's precision).
        int occlusionFailures = 0;
        for (float distance : {.5f, 2.f, 8.f, 32.f, 100.f}) {
            for (bool reverse : {false, true}) {
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                auto flat = [&](float d, float layer, bool hidden) {
                    const float vertices[] = {-d,-d,-d, 3*d,-d,-d, -d,3*d,-d};
                    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STREAM_DRAW);
                    glVertexAttrib1f(4, layer);
                    glVertexAttrib3f(2, hidden ? 1 : 0, hidden ? 0 : 1, 0);
                    glDrawArrays(GL_TRIANGLES, 0, 3);
                };
                float separation = distance > 32 ? .1f : .02f;
                if (reverse) { flat(distance+separation,16,true); flat(distance,0,false); }
                else { flat(distance,0,false); flat(distance+separation,16,true); }
                std::array<unsigned char,4> result{};
                glReadPixels(64,64,1,1,GL_RGBA,GL_UNSIGNED_BYTE,result.data());
                if (result[0] > 10 || result[1] < 240) occlusionFailures++;
            }
        }
        std::cout << "physicalOcclusion cases=10 failures=" << occlusionFailures << '\n';
        if (occlusionFailures || glGetError()!=GL_NO_ERROR)
            throw std::runtime_error("Physical occlusion/depth probe failed");
        CGLSetCurrentContext(nullptr);
        CGLDestroyContext(context);
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        CGLSetCurrentContext(nullptr);
        if (context) CGLDestroyContext(context);
        return 1;
    }
}
