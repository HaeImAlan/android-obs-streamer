/*
 * obs-android-usbcam — Native OBS source plugin
 *
 * Two transport modes:
 *   - "raw"   : Raw TCP (port 4748). "ACAM" magic + [u32 BE size][JPEG]
 *   - "http"  : Legacy MJPEG over HTTP (port 4747). multipart/x-mixed-replace
 *
 * Both modes decode JPEG with stb_image and push frames straight into OBS.
 */

#include <obs-module.h>
#include <util/platform.h>
#include <util/threading.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_JPEG
#define STBI_NO_STDIO
#include "stb_image.h"

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#pragma comment(lib, "ws2_32.lib")
typedef SOCKET sock_t;
#define SOCK_INVALID INVALID_SOCKET
#define sock_close closesocket
#define sock_errno WSAGetLastError()
#else
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <unistd.h>
#include <errno.h>
typedef int sock_t;
#define SOCK_INVALID (-1)
#define sock_close close
#define sock_errno errno
#endif

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE("obs-android-usbcam", "en-US")

MODULE_EXPORT const char *obs_module_description(void)
{
	return "Android USB Cam — low-latency camera source (raw TCP + MJPEG)";
}

/* ── Source data ───────────────────────────────────────────────────── */

#define MODE_RAW  0
#define MODE_HTTP 1

struct usbcam_source {
	obs_source_t *source;
	char *host;
	int port;
	int mode;            /* MODE_RAW or MODE_HTTP */
	bool reconnect;
	int reconnect_ms;

	pthread_t thread;
	volatile bool running;
	volatile bool connected;

	uint8_t *recv_buf;
	size_t recv_buf_size;
};

/* ── Network helpers ──────────────────────────────────────────────── */

static void net_init(void)
{
#ifdef _WIN32
	static bool inited = false;
	if (!inited) {
		WSADATA wsa;
		WSAStartup(MAKEWORD(2, 2), &wsa);
		inited = true;
	}
#endif
}

static sock_t connect_to(const char *host, int port)
{
	struct addrinfo hints, *res = NULL, *p;
	char portstr[16];
	sock_t s = SOCK_INVALID;
	int flag = 1;

	snprintf(portstr, sizeof(portstr), "%d", port);
	memset(&hints, 0, sizeof(hints));
	hints.ai_family = AF_UNSPEC;
	hints.ai_socktype = SOCK_STREAM;

	if (getaddrinfo(host, portstr, &hints, &res) != 0)
		return SOCK_INVALID;

	for (p = res; p; p = p->ai_next) {
		s = socket(p->ai_family, p->ai_socktype, p->ai_protocol);
		if (s == SOCK_INVALID)
			continue;

		struct timeval tv;
		tv.tv_sec = 3;
		tv.tv_usec = 0;
		setsockopt(s, SOL_SOCKET, SO_RCVTIMEO,
			   (const char *)&tv, sizeof(tv));

		if (connect(s, p->ai_addr, (int)p->ai_addrlen) == 0)
			break;

		sock_close(s);
		s = SOCK_INVALID;
	}
	freeaddrinfo(res);

	if (s == SOCK_INVALID)
		return SOCK_INVALID;

	setsockopt(s, IPPROTO_TCP, TCP_NODELAY,
		   (const char *)&flag, sizeof(flag));

	int bufsize = 65536;
	setsockopt(s, SOL_SOCKET, SO_RCVBUF,
		   (const char *)&bufsize, sizeof(bufsize));

	return s;
}

static bool recv_exact(sock_t s, void *buf, size_t len)
{
	size_t got = 0;
	while (got < len) {
		int n = recv(s, (char *)buf + got, (int)(len - got), 0);
		if (n <= 0)
			return false;
		got += (size_t)n;
	}
	return true;
}

static bool send_all(sock_t s, const char *buf, size_t len)
{
	size_t sent = 0;
	while (sent < len) {
		int n = send(s, buf + sent, (int)(len - sent), 0);
		if (n <= 0)
			return false;
		sent += (size_t)n;
	}
	return true;
}

/* ── Frame buffer / decoder ───────────────────────────────────────── */

static void ensure_buf(struct usbcam_source *ctx, size_t need)
{
	if (ctx->recv_buf_size >= need)
		return;
	ctx->recv_buf = brealloc(ctx->recv_buf, need);
	ctx->recv_buf_size = need;
}

static void push_frame(struct usbcam_source *ctx, const uint8_t *jpeg,
		       size_t jpeg_size)
{
	int w, h, channels;
	unsigned char *rgb = stbi_load_from_memory(jpeg, (int)jpeg_size,
						   &w, &h, &channels, 3);
	if (!rgb)
		return;

	struct obs_source_frame frame;
	memset(&frame, 0, sizeof(frame));
	frame.width = (uint32_t)w;
	frame.height = (uint32_t)h;
	frame.format = VIDEO_FORMAT_BGR3;

	size_t pixels = (size_t)w * (size_t)h;
	for (size_t i = 0; i < pixels; i++) {
		uint8_t tmp = rgb[i * 3];
		rgb[i * 3] = rgb[i * 3 + 2];
		rgb[i * 3 + 2] = tmp;
	}

	frame.data[0] = rgb;
	frame.linesize[0] = (uint32_t)(w * 3);
	frame.timestamp = os_gettime_ns();

	obs_source_output_video(ctx->source, &frame);
	stbi_image_free(rgb);
}

/* ── Mode: RAW TCP ────────────────────────────────────────────────── */

static bool run_raw_session(struct usbcam_source *ctx, sock_t s)
{
	char magic[4];
	if (!recv_exact(s, magic, 4) || memcmp(magic, "ACAM", 4) != 0) {
		blog(LOG_WARNING, "[android-usbcam] Bad magic");
		return false;
	}

	while (ctx->running) {
		uint8_t hdr[4];
		if (!recv_exact(s, hdr, 4))
			return false;

		uint32_t frame_size = ((uint32_t)hdr[0] << 24) |
				      ((uint32_t)hdr[1] << 16) |
				      ((uint32_t)hdr[2] << 8) |
				      ((uint32_t)hdr[3]);

		if (frame_size == 0 || frame_size > 10 * 1024 * 1024)
			return false;

		ensure_buf(ctx, frame_size);
		if (!recv_exact(s, ctx->recv_buf, frame_size))
			return false;

		push_frame(ctx, ctx->recv_buf, frame_size);
	}
	return true;
}

/* ── Mode: HTTP MJPEG ─────────────────────────────────────────────── */

/* Simple line-buffered HTTP reader for the headers, then raw byte parser
 * for the multipart body. We look for SOI/EOI markers (FFD8 / FFD9) to
 * delimit JPEG frames — robust regardless of boundary string. */

static int read_line(sock_t s, char *line, int max)
{
	int i = 0;
	while (i < max - 1) {
		char c;
		int n = recv(s, &c, 1, 0);
		if (n <= 0)
			return -1;
		line[i++] = c;
		if (c == '\n')
			break;
	}
	line[i] = 0;
	return i;
}

static bool run_http_session(struct usbcam_source *ctx, sock_t s)
{
	/* Send HTTP GET */
	char req[256];
	int reqlen = snprintf(req, sizeof(req),
			      "GET / HTTP/1.0\r\n"
			      "Host: %s\r\n"
			      "User-Agent: obs-android-usbcam/1.0\r\n"
			      "Accept: */*\r\n"
			      "\r\n",
			      ctx->host);
	if (!send_all(s, req, (size_t)reqlen))
		return false;

	/* Skip response headers */
	char line[1024];
	for (;;) {
		int n = read_line(s, line, sizeof(line));
		if (n < 0)
			return false;
		if (n <= 2) /* empty line = end of headers */
			break;
	}

	/* Streaming JPEG parser using SOI/EOI scanning.
	 * Read into a sliding buffer; whenever we have a full FFD8...FFD9,
	 * push it as a frame. */
	const size_t CHUNK = 16384;
	size_t cap = 256 * 1024;
	uint8_t *buf = bmalloc(cap);
	size_t len = 0;
	bool ok = true;

	while (ctx->running) {
		if (cap - len < CHUNK) {
			cap *= 2;
			if (cap > 16 * 1024 * 1024) { ok = false; break; }
			buf = brealloc(buf, cap);
		}

		int n = recv(s, (char *)buf + len, (int)CHUNK, 0);
		if (n <= 0) { ok = false; break; }
		len += (size_t)n;

		/* Find SOI then EOI */
		size_t scan = 0;
		while (scan + 1 < len) {
			/* Find FFD8 */
			size_t soi = (size_t)-1;
			for (size_t i = scan; i + 1 < len; i++) {
				if (buf[i] == 0xFF && buf[i+1] == 0xD8) {
					soi = i;
					break;
				}
			}
			if (soi == (size_t)-1) {
				/* Drop everything except trailing 0xFF */
				if (len > 0 && buf[len - 1] == 0xFF) {
					buf[0] = 0xFF;
					len = 1;
				} else {
					len = 0;
				}
				break;
			}

			/* Find FFD9 after SOI */
			size_t eoi = (size_t)-1;
			for (size_t i = soi + 2; i + 1 < len; i++) {
				if (buf[i] == 0xFF && buf[i+1] == 0xD9) {
					eoi = i + 2; /* exclusive */
					break;
				}
			}
			if (eoi == (size_t)-1) {
				/* Need more data — keep from SOI onward */
				if (soi > 0) {
					memmove(buf, buf + soi, len - soi);
					len -= soi;
				}
				break;
			}

			push_frame(ctx, buf + soi, eoi - soi);

			/* Advance past this frame */
			memmove(buf, buf + eoi, len - eoi);
			len -= eoi;
			scan = 0;
		}
	}

	bfree(buf);
	return ok;
}

/* ── Worker thread ────────────────────────────────────────────────── */

static void *stream_thread(void *data)
{
	struct usbcam_source *ctx = data;
	net_init();

	while (ctx->running) {
		blog(LOG_INFO,
		     "[android-usbcam] %s mode → connecting to %s:%d ...",
		     ctx->mode == MODE_RAW ? "raw" : "http",
		     ctx->host, ctx->port);

		sock_t s = connect_to(ctx->host, ctx->port);
		if (s == SOCK_INVALID) {
			if (!ctx->reconnect || !ctx->running)
				break;
			os_sleep_ms((uint32_t)ctx->reconnect_ms);
			continue;
		}

		ctx->connected = true;
		blog(LOG_INFO, "[android-usbcam] Connected");

		if (ctx->mode == MODE_RAW)
			run_raw_session(ctx, s);
		else
			run_http_session(ctx, s);

		ctx->connected = false;
		sock_close(s);
		blog(LOG_INFO, "[android-usbcam] Disconnected");

		if (!ctx->reconnect || !ctx->running)
			break;
		os_sleep_ms((uint32_t)ctx->reconnect_ms);
	}

	return NULL;
}

/* ── OBS source callbacks ─────────────────────────────────────────── */

static const char *usbcam_name(void *unused)
{
	UNUSED_PARAMETER(unused);
	return "Android USB Cam";
}

static void load_settings(struct usbcam_source *ctx, obs_data_t *settings)
{
	bfree(ctx->host);
	ctx->host = bstrdup(obs_data_get_string(settings, "host"));
	const char *mode_s = obs_data_get_string(settings, "mode");
	ctx->mode = (mode_s && strcmp(mode_s, "http") == 0) ? MODE_HTTP : MODE_RAW;
	ctx->port = (int)obs_data_get_int(settings, "port");
	if (ctx->port <= 0)
		ctx->port = (ctx->mode == MODE_RAW) ? 4748 : 4747;
	ctx->reconnect = obs_data_get_bool(settings, "reconnect");
	ctx->reconnect_ms = (int)obs_data_get_int(settings, "reconnect_ms");
	if (ctx->reconnect_ms <= 0)
		ctx->reconnect_ms = 1000;
}

static void *usbcam_create(obs_data_t *settings, obs_source_t *source)
{
	struct usbcam_source *ctx = bzalloc(sizeof(*ctx));
	ctx->source = source;
	load_settings(ctx, settings);
	ctx->running = true;
	pthread_create(&ctx->thread, NULL, stream_thread, ctx);
	return ctx;
}

static void usbcam_destroy(void *data)
{
	struct usbcam_source *ctx = data;
	ctx->running = false;
	pthread_join(ctx->thread, NULL);
	bfree(ctx->recv_buf);
	bfree(ctx->host);
	bfree(ctx);
}

static void usbcam_update(void *data, obs_data_t *settings)
{
	struct usbcam_source *ctx = data;

	ctx->running = false;
	pthread_join(ctx->thread, NULL);

	load_settings(ctx, settings);

	ctx->running = true;
	pthread_create(&ctx->thread, NULL, stream_thread, ctx);
}

static bool mode_changed(obs_properties_t *props, obs_property_t *p,
			 obs_data_t *settings)
{
	UNUSED_PARAMETER(p);
	const char *mode = obs_data_get_string(settings, "mode");
	bool is_http = mode && strcmp(mode, "http") == 0;

	/* Auto-set port to mode default if it currently matches the
	 * other mode's default. */
	int port = (int)obs_data_get_int(settings, "port");
	if (is_http && port == 4748)
		obs_data_set_int(settings, "port", 4747);
	else if (!is_http && port == 4747)
		obs_data_set_int(settings, "port", 4748);

	obs_property_t *port_prop = obs_properties_get(props, "port");
	obs_property_set_description(port_prop,
		is_http ? "Port (HTTP, default 4747)"
			: "Port (raw TCP, default 4748)");
	return true;
}

static obs_properties_t *usbcam_properties(void *unused)
{
	UNUSED_PARAMETER(unused);
	obs_properties_t *props = obs_properties_create();

	obs_property_t *mode = obs_properties_add_list(
		props, "mode", "Transport mode",
		OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_STRING);
	obs_property_list_add_string(mode, "Raw TCP (low latency)", "raw");
	obs_property_list_add_string(mode, "HTTP MJPEG (legacy)", "http");
	obs_property_set_modified_callback(mode, mode_changed);

	obs_properties_add_text(props, "host",
		"Host (phone IP, or 127.0.0.1 for ADB forward)",
		OBS_TEXT_DEFAULT);
	obs_properties_add_int(props, "port",
		"Port (raw TCP, default 4748)", 1, 65535, 1);
	obs_properties_add_bool(props, "reconnect", "Auto-reconnect");
	obs_properties_add_int(props, "reconnect_ms",
		"Reconnect delay (ms)", 100, 10000, 100);

	return props;
}

static void usbcam_defaults(obs_data_t *settings)
{
	obs_data_set_default_string(settings, "mode", "raw");
	obs_data_set_default_string(settings, "host", "127.0.0.1");
	obs_data_set_default_int(settings, "port", 4748);
	obs_data_set_default_bool(settings, "reconnect", true);
	obs_data_set_default_int(settings, "reconnect_ms", 1000);
}

static struct obs_source_info usbcam_source_info = {
	.id           = "android_usbcam",
	.type         = OBS_SOURCE_TYPE_INPUT,
	.output_flags = OBS_SOURCE_ASYNC_VIDEO | OBS_SOURCE_DO_NOT_DUPLICATE,
	.icon_type    = OBS_ICON_TYPE_CAMERA,
	.get_name     = usbcam_name,
	.create       = usbcam_create,
	.destroy      = usbcam_destroy,
	.update       = usbcam_update,
	.get_properties = usbcam_properties,
	.get_defaults = usbcam_defaults,
};

/* ── Module entry ─────────────────────────────────────────────────── */

bool obs_module_load(void)
{
	obs_register_source(&usbcam_source_info);
	blog(LOG_INFO, "[android-usbcam] Plugin loaded "
	     "(source id: android_usbcam)");
	return true;
}

void obs_module_unload(void)
{
	blog(LOG_INFO, "[android-usbcam] Plugin unloaded");
}
