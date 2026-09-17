// Compile the actual embedded renderer shaders and exercise light-only updates in
// an offscreen macOS OpenGL context. No game instance, simulation or asset needed.
// Build: clang++ -std=c++17 -Wno-deprecated-declarations -framework OpenGL
//        tools/check_world_shader.cpp -o .local/build/check_world_shader
#include <OpenGL/OpenGL.h>
#include <OpenGL/gl.h>
#include <OpenGL/glext.h>
#include <array>
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
        GLenum error = glGetError();
        if (error != GL_NO_ERROR || bright < 240 || dim < 120 || dim > 135 || sourceDim != 128)
            throw std::runtime_error("Light-only render failed: bright=" + std::to_string(bright)
                    + " dim=" + std::to_string(dim) + " GL=" + std::to_string(error));
        std::cout << "renderer=" << glGetString(GL_RENDERER) << "\nversion=" << glGetString(GL_VERSION)
                  << "\nshaderCompileLink=passed\nlightOnlyUpdate=passed bright=" << bright
                  << " dim=" << dim << " sourceDim=" << sourceDim << " geometryUploads=1 lightUploads=2\n";
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
