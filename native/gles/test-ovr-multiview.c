/* SPDX-License-Identifier: MIT
 * DriverLab: GL_OVR_multiview test in the style of the freedreno tests-3d
 * programs (uses their test-util-3d.h). Renders one draw into the two layers
 * of a 2D array texture at once; layer 0 must come out red, layer 1 blue.
 * Exit 0 = pass, 1 = wrong pixels, 77 = extension not supported.
 */
#include <GLES3/gl3.h>
#include "test-util-3d.h"

#ifndef GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_NUM_VIEWS_OVR
#define GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_NUM_VIEWS_OVR 0x9630
#endif
typedef void (*PFN_FramebufferTextureMultiviewOVR)(GLenum target, GLenum attachment, GLuint texture,
		GLint level, GLint baseViewIndex, GLsizei numViews);

static EGLint const config_attribute_list[] = {
	EGL_RED_SIZE, 8,
	EGL_GREEN_SIZE, 8,
	EGL_BLUE_SIZE, 8,
	EGL_ALPHA_SIZE, 8,
	EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
	EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */,
	EGL_NONE
};

static const EGLint context_attribute_list[] = {
	EGL_CONTEXT_CLIENT_VERSION, 3,
	EGL_NONE
};

static const char *vertex_shader_source =
	"#version 300 es\n"
	"#extension GL_OVR_multiview : require\n"
	"layout(num_views = 2) in;\n"
	"in vec4 in_position;\n"
	"flat out int view;\n"
	"void main()\n"
	"{\n"
	"    gl_Position = in_position;\n"
	"    view = int(gl_ViewID_OVR);\n"
	"}\n";

static const char *fragment_shader_source =
	"#version 300 es\n"
	"precision mediump float;\n"
	"flat in int view;\n"
	"out vec4 color;\n"
	"void main()\n"
	"{\n"
	"    color = view == 0 ? vec4(1.0, 0.0, 0.0, 1.0) : vec4(0.0, 0.0, 1.0, 1.0);\n"
	"}\n";

int main(int argc, char **argv)
{
	const int size = 64;
	EGLDisplay display;
	EGLConfig config;
	EGLint num_config;
	EGLContext context;
	EGLSurface surface;
	GLuint program, tex, fbo, readfbo;
	GLfloat quad[] = {
		-1.0, -1.0, 0.0,
		 1.0, -1.0, 0.0,
		-1.0,  1.0, 0.0,
		 1.0,  1.0, 0.0,
	};
	int failures = 0;

	RD_START("ovr-multiview", "views=2, size=%dx%d", size, size);

	display = get_display();
	ECHK(eglChooseConfig(display, config_attribute_list, &config, 1, &num_config));
	ECHK(context = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attribute_list));
	surface = make_window(display, config, size, size);
	ECHK(eglMakeCurrent(display, surface, surface, context));

	const char *ext = (const char *)glGetString(GL_EXTENSIONS);
	if (!ext || !strstr(ext, "GL_OVR_multiview")) {
		printf("GL_OVR_multiview is not supported by this driver\n");
		RD_END();
		return 77;
	}
	PFN_FramebufferTextureMultiviewOVR fbtm =
		(PFN_FramebufferTextureMultiviewOVR)eglGetProcAddress("glFramebufferTextureMultiviewOVR");
	if (!fbtm) {
		printf("glFramebufferTextureMultiviewOVR not found\n");
		RD_END();
		return 77;
	}

	program = get_program(vertex_shader_source, fragment_shader_source);
	GCHK(glBindAttribLocation(program, 0, "in_position"));
	link_program(program);

	GCHK(glGenTextures(1, &tex));
	GCHK(glBindTexture(GL_TEXTURE_2D_ARRAY, tex));
	GCHK(glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1, GL_RGBA8, size, size, 2));

	GCHK(glGenFramebuffers(1, &fbo));
	GCHK(glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo));
	GCHK(fbtm(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, tex, 0, 0, 2));
	GLenum status = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
	if (status != GL_FRAMEBUFFER_COMPLETE) {
		printf("multiview framebuffer incomplete: 0x%x\n", status);
		RD_END();
		return 1;
	}

	GCHK(glViewport(0, 0, size, size));
	GCHK(glClearColor(0.0, 1.0, 0.0, 1.0));
	GCHK(glClear(GL_COLOR_BUFFER_BIT));
	GCHK(glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 0, quad));
	GCHK(glEnableVertexAttribArray(0));
	GCHK(glDrawArrays(GL_TRIANGLE_STRIP, 0, 4));
	GCHK(glFinish());

	GCHK(glGenFramebuffers(1, &readfbo));
	GCHK(glBindFramebuffer(GL_READ_FRAMEBUFFER, readfbo));
	for (int layer = 0; layer < 2; layer++) {
		unsigned char px[4] = {0};
		const unsigned char want[2][4] = { {255, 0, 0, 255}, {0, 0, 255, 255} };
		GCHK(glFramebufferTextureLayer(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, tex, 0, layer));
		GCHK(glReadPixels(size / 2, size / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px));
		int ok = px[0] == want[layer][0] && px[1] == want[layer][1] && px[2] == want[layer][2];
		printf("layer %d: %02x%02x%02x%02x %s\n", layer, px[0], px[1], px[2], px[3], ok ? "ok" : "WRONG");
		if (!ok)
			failures++;
	}

	ECHK(eglDestroySurface(display, surface));
	ECHK(eglTerminate(display));
	RD_END();
	printf("%s\n", failures ? "FAIL" : "PASS");
	return failures ? 1 : 0;
}
