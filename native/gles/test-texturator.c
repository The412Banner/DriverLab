/* SPDX-License-Identifier: MIT
 * DriverLab: texture upload and sampling test in the style of the freedreno
 * tests-3d programs (uses their test-util-3d.h).
 *
 * Uploads a known pattern to 2D (all mip levels), 2D array and 3D textures in
 * several formats, reads every texel back through texelFetch() into an RGBA8
 * framebuffer and checks it. Run without arguments for the default sweep, or:
 *   test-texturator <2D|2DArray|3D> <format> <WxH[xD]>
 * Exit 0 = all texels right, 1 = wrong texels.
 */
#include <GLES3/gl3.h>
#include <math.h>
#include "test-util-3d.h"

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

enum target { T2D, T2DARRAY, T3D };
static const char *target_names[] = { "2D", "2DArray", "3D" };

struct tfmt {
	const char *name;
	GLenum ifmt, fmt, type;
	int comps;          /* channels compared (missing ones read as 0 / alpha 1) */
	int bits[4];        /* precision per channel for the expected value */
	int srgb, flt;
};

static const struct tfmt formats[] = {
	{ "RGBA8",        GL_RGBA8,        GL_RGBA, GL_UNSIGNED_BYTE, 4, {8, 8, 8, 8}, 0, 0 },
	{ "RG8",          GL_RG8,          GL_RG,   GL_UNSIGNED_BYTE, 2, {8, 8, 0, 0}, 0, 0 },
	{ "R8",           GL_R8,           GL_RED,  GL_UNSIGNED_BYTE, 1, {8, 0, 0, 0}, 0, 0 },
	{ "RGB565",       GL_RGB565,       GL_RGB,  GL_UNSIGNED_BYTE, 3, {5, 6, 5, 0}, 0, 0 },
	{ "RGBA4",        GL_RGBA4,        GL_RGBA, GL_UNSIGNED_BYTE, 4, {4, 4, 4, 4}, 0, 0 },
	{ "RGB5_A1",      GL_RGB5_A1,      GL_RGBA, GL_UNSIGNED_BYTE, 4, {5, 5, 5, 1}, 0, 0 },
	{ "SRGB8_ALPHA8", GL_SRGB8_ALPHA8, GL_RGBA, GL_UNSIGNED_BYTE, 4, {8, 8, 8, 8}, 1, 0 },
	{ "RGBA16F",      GL_RGBA16F,      GL_RGBA, GL_FLOAT,         4, {8, 8, 8, 8}, 0, 1 },
	{ "R32F",         GL_R32F,         GL_RED,  GL_FLOAT,         1, {8, 0, 0, 0}, 0, 1 },
};
#define NFORMATS (sizeof(formats) / sizeof(formats[0]))

static EGLDisplay display;
static EGLSurface surface;
static EGLContext context;

static unsigned char pattern(int x, int y, int z, int level, int c)
{
	switch (c) {
	case 0: return (unsigned char)(x * 7 + z * 31 + level * 41);
	case 1: return (unsigned char)(y * 13 + z * 17 + level * 23);
	case 2: return (unsigned char)(x * y + z * 5 + level * 11);
	default: return (unsigned char)((x + y + z * 3 + level) * 3 + 128);
	}
}

static int expected(const struct tfmt *f, unsigned char v, int c)
{
	if (c >= f->comps)
		return c == 3 ? 255 : 0;
	int bits = f->bits[c];
	double x = v / 255.0;
	if (bits < 8) {
		double m = (1 << bits) - 1;
		x = round(x * m) / m;
	}
	if (f->srgb && c < 3)
		x = x <= 0.04045 ? x / 12.92 : pow((x + 0.055) / 1.055, 2.4);
	return (int)lround(x * 255.0);
}

static const char *fs_for(enum target t)
{
	switch (t) {
	case T2D:
		return "#version 300 es\nprecision highp float;\nuniform highp sampler2D tex;\nuniform int lvl;\nuniform int layer;\n"
		       "out vec4 c;\nvoid main() { c = texelFetch(tex, ivec2(gl_FragCoord.xy), lvl); }\n";
	case T2DARRAY:
		return "#version 300 es\nprecision highp float;\nuniform highp sampler2DArray tex;\nuniform int lvl;\nuniform int layer;\n"
		       "out vec4 c;\nvoid main() { c = texelFetch(tex, ivec3(ivec2(gl_FragCoord.xy), layer), lvl); }\n";
	default:
		return "#version 300 es\nprecision highp float;\nuniform highp sampler3D tex;\nuniform int lvl;\nuniform int layer;\n"
		       "out vec4 c;\nvoid main() { c = texelFetch(tex, ivec3(ivec2(gl_FragCoord.xy), layer), lvl); }\n";
	}
}

static const char *vs_source =
	"#version 300 es\nin vec4 in_position;\nvoid main() { gl_Position = in_position; }\n";

static void *make_level(const struct tfmt *f, int w, int h, int z, int level)
{
	int n = 0;
	switch (f->fmt) {
	case GL_RED: n = 1; break;
	case GL_RG: n = 2; break;
	case GL_RGB: n = 3; break;
	default: n = 4; break;
	}
	if (f->type == GL_FLOAT) {
		float *p = malloc((size_t)w * h * n * sizeof(float));
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++)
				for (int c = 0; c < n; c++)
					p[(y * w + x) * n + c] = pattern(x, y, z, level, c) / 255.0f;
		return p;
	}
	unsigned char *p = malloc((size_t)w * h * n);
	for (int y = 0; y < h; y++)
		for (int x = 0; x < w; x++)
			for (int c = 0; c < n; c++)
				p[(y * w + x) * n + c] = pattern(x, y, z, level, c);
	return p;
}

static int run_case(enum target t, const struct tfmt *f, int w, int h, int d)
{
	GLenum gltarget = t == T2D ? GL_TEXTURE_2D : t == T2DARRAY ? GL_TEXTURE_2D_ARRAY : GL_TEXTURE_3D;
	int levels = 1;
	if (t == T2D) {
		int mw = w, mh = h;
		while (mw > 1 || mh > 1) {
			mw = mw > 1 ? mw / 2 : 1;
			mh = mh > 1 ? mh / 2 : 1;
			levels++;
		}
	}
	if (t == T2D)
		d = 1;

	RD_START("texturator", "target=%s, format=%s, size=%dx%dx%d, levels=%d",
			target_names[t], f->name, w, h, d, levels);

	GLuint program = get_program(vs_source, fs_for(t));
	GCHK(glBindAttribLocation(program, 0, "in_position"));
	link_program(program);

	GLuint tex;
	GCHK(glGenTextures(1, &tex));
	GCHK(glActiveTexture(GL_TEXTURE0));
	GCHK(glBindTexture(gltarget, tex));
	if (t == T2D)
		GCHK(glTexStorage2D(gltarget, levels, f->ifmt, w, h));
	else
		GCHK(glTexStorage3D(gltarget, levels, f->ifmt, w, h, d));
	GCHK(glPixelStorei(GL_UNPACK_ALIGNMENT, 1));
	for (int l = 0; l < levels; l++) {
		int lw = w >> l ? w >> l : 1, lh = h >> l ? h >> l : 1;
		for (int z = 0; z < d; z++) {
			void *p = make_level(f, lw, lh, z, l);
			if (t == T2D)
				GCHK(glTexSubImage2D(gltarget, l, 0, 0, lw, lh, f->fmt, f->type, p));
			else
				GCHK(glTexSubImage3D(gltarget, l, 0, 0, z, lw, lh, 1, f->fmt, f->type, p));
			free(p);
		}
	}
	GCHK(glTexParameteri(gltarget, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST));
	GCHK(glTexParameteri(gltarget, GL_TEXTURE_MAG_FILTER, GL_NEAREST));
	GCHK(glUniform1i(glGetUniformLocation(program, "tex"), 0));

	GLuint rb, fbo;
	GCHK(glGenRenderbuffers(1, &rb));
	GCHK(glBindRenderbuffer(GL_RENDERBUFFER, rb));
	GCHK(glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, w, h));
	GCHK(glGenFramebuffers(1, &fbo));
	GCHK(glBindFramebuffer(GL_FRAMEBUFFER, fbo));
	GCHK(glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rb));

	static const GLfloat quad[] = { -1, -1, 0, 1, -1, 0, -1, 1, 0, 1, 1, 0 };
	GCHK(glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 0, quad));
	GCHK(glEnableVertexAttribArray(0));

	int bad = 0, checked = 0;
	unsigned char *px = malloc((size_t)w * h * 4);
	for (int l = 0; l < levels; l++) {
		int lw = w >> l ? w >> l : 1, lh = h >> l ? h >> l : 1;
		for (int z = 0; z < d; z++) {
			GCHK(glUniform1i(glGetUniformLocation(program, "lvl"), l));
			GCHK(glUniform1i(glGetUniformLocation(program, "layer"), z));
			GCHK(glViewport(0, 0, lw, lh));
			GCHK(glDrawArrays(GL_TRIANGLE_STRIP, 0, 4));
			GCHK(glReadPixels(0, 0, lw, lh, GL_RGBA, GL_UNSIGNED_BYTE, px));
			for (int y = 0; y < lh; y++) {
				for (int x = 0; x < lw; x++) {
					for (int c = 0; c < 4; c++) {
						int want = expected(f, pattern(x, y, z, l, c), c);
						int got = px[(y * lw + x) * 4 + c];
						checked++;
						if (abs(got - want) > 2) {
							if (bad < 8)
								printf("  mismatch level %d layer %d texel %d,%d ch %d: got %d want %d\n",
										l, z, x, y, c, got, want);
							bad++;
						}
					}
				}
			}
		}
	}
	free(px);
	GCHK(glDeleteFramebuffers(1, &fbo));
	GCHK(glDeleteRenderbuffers(1, &rb));
	GCHK(glDeleteTextures(1, &tex));
	GCHK(glDeleteProgram(program));
	RD_END();
	printf("%-8s %-13s %dx%dx%d levels %d: %s (%d of %d values wrong)\n",
			target_names[t], f->name, w, h, d, levels, bad ? "FAIL" : "ok", bad, checked);
	return bad ? 1 : 0;
}

static void usage(const char *argv0)
{
	printf("usage: %s [<2D|2DArray|3D> <format> <WxH[xD]>]\n", argv0);
	printf("formats:");
	for (unsigned i = 0; i < NFORMATS; i++)
		printf(" %s", formats[i].name);
	printf("\nwithout arguments every format is tested as 2D 37x65 (all mip levels),"
	       " 2DArray 37x65x4 and 3D 16x16x4.\n");
}

int main(int argc, char **argv)
{
	EGLConfig config;
	EGLint num_config;
	int failures = 0;

	if (argc > 1 && (!strcmp(argv[1], "-h") || !strcmp(argv[1], "--help"))) {
		usage(argv[0]);
		return 0;
	}

	display = get_display();
	ECHK(eglChooseConfig(display, config_attribute_list, &config, 1, &num_config));
	ECHK(context = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attribute_list));
	surface = make_window(display, config, 64, 64);
	ECHK(eglMakeCurrent(display, surface, surface, context));

	if (argc >= 4) {
		enum target t = !strcmp(argv[1], "3D") ? T3D : !strcmp(argv[1], "2DArray") ? T2DARRAY : T2D;
		const struct tfmt *f = NULL;
		for (unsigned i = 0; i < NFORMATS; i++)
			if (!strcmp(argv[2], formats[i].name))
				f = &formats[i];
		int w = 0, h = 0, d = 1;
		if (!f || sscanf(argv[3], "%dx%dx%d", &w, &h, &d) < 2 || w < 1 || h < 1 || d < 1) {
			usage(argv[0]);
			return 2;
		}
		failures += run_case(t, f, w, h, d);
	} else {
		for (unsigned i = 0; i < NFORMATS; i++) {
			failures += run_case(T2D, &formats[i], 37, 65, 1);
			failures += run_case(T2DARRAY, &formats[i], 37, 65, 4);
			failures += run_case(T3D, &formats[i], 16, 16, 4);
		}
	}

	ECHK(eglDestroySurface(display, surface));
	ECHK(eglTerminate(display));
	printf("%s: %d case(s) failed\n", failures ? "FAIL" : "PASS", failures);
	return failures ? 1 : 0;
}
