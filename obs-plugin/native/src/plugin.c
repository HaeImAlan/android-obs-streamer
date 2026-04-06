/*
 * obs-android-usbcam — Native OBS source plugin
 *
 * Connects to the Android USB Cam raw TCP server (port 4748).
 * Protocol: "ACAM" magic → repeating [uint32 BE size][JPEG bytes]
 * Decodes JPEG and pushes frames directly into OBS.
 *
 * Build: cmake + OBS SDK (see CMakeLists.txt)
 */

#include <obs-module.h>
#include <util/platform.h>
#include <util/threading.h>

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
	return "Android USB Cam — low-latency camera source";
}

/* ── Source data ───────────────────────────────────────────────────── */

struct usbcam_source {
	obs_source_t *source;
	char *host;
	int port;
	bool reconnect;
	int reconnect_ms;

	pthread_t thread;
	volatile bool running;
	volatile bool connected;

	/* Frame buffer */
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
	struct sockaddr_in addr;
	sock_t s;
	int flag = 1;

	s = socket(AF_INET, SOCK_STREAM, 0);
	if (s == SOCK_INVALID)
		return SOCK_INVALID;

	memset(&addr, 0, sizeof(addr));
	addr.sin_family = AF_INET;
	addr.sin_port = htons((uint16_t)port);

	if (inet_pton(AF_INET, host, &addr.sin_addr) != 1) {
		sock_close(s);
		return SOCK_INVALID;
	}

	/* Set timeout for connect */
	struct timeval tv;
	tv.tv_sec = 3;
	tv.tv_usec = 0;
	setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, (const char *)&tv, sizeof(tv));

	if (connect(s, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
		sock_close(s);
		return SOCK_INVALID;
	}

	/* TCP_NODELAY for low latency */
	setsockopt(s, IPPROTO_TCP, TCP_NODELAY, (const char *)&flag,
		   sizeof(flag));

	/* Smaller recv buffer to reduce kernel-side latency */
	int bufsize = 65536;
	setsockopt(s, SOL_SOCKET, SO_RCVBUF, (const char *)&bufsize,
		   sizeof(bufsize));

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

/* ── Worker thread ────────────────────────────────────────────────── */

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
	unsigned char *rgb =
		stbi_load_from_memory(jpeg, (int)jpeg_size, &w, &h,
				      &channels, 3);
	if (!rgb)
		return;

	struct obs_source_frame frame;
	memset(&frame, 0, sizeof(frame));
	frame.width = (uint32_t)w;
	frame.height = (uint32_t)h;
	frame.format = VIDEO_FORMAT_BGR3; /* stb outputs RGB, OBS wants BGR3 */

	/* Swap R and B in-place — stb gives RGB, OBS BGR3 */
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

static void *stream_thread(void *data)
{
	struct usbcam_source *ctx = data;
	net_init();

	while (ctx->running) {
		blog(LOG_INFO, "[android-usbcam] Connecting to %s:%d...",
		     ctx->host, ctx->port);

		sock_t s = connect_to(ctx->host, ctx->port);
		if (s == SOCK_INVALID) {
			if (!ctx->reconnect || !ctx->running)
				break;
			os_sleep_ms((uint32_t)ctx->reconnect_ms);
			continue;
		}

		/* Read magic "ACAM" */
		char magic[4];
		if (!recv_exact(s, magic, 4) ||
		    memcmp(magic, "ACAM", 4) != 0) {
			blog(LOG_WARNING,
			     "[android-usbcam] Bad magic, disconnecting");
			sock_close(s);
			if (ctx->reconnect && ctx->running) {
				os_sleep_ms((uint32_t)ctx->reconnect_ms);
				continue;
			}
			break;
		}

		ctx->connected = true;
		blog(LOG_INFO, "[android-usbcam] Connected");

		/* Frame loop */
		while (ctx->running) {
			/* Read 4-byte big-endian size */
			uint8_t hdr[4];
			if (!recv_exact(s, hdr, 4))
				break;

			uint32_t frame_size = ((uint32_t)hdr[0] << 24) |
					      ((uint32_t)hdr[1] << 16) |
					      ((uint32_t)hdr[2] << 8) |
					      ((uint32_t)hdr[3]);

			if (frame_size == 0 || frame_size > 10 * 1024 * 1024)
				break; /* Sanity check */

			ensure_buf(ctx, frame_size);

			if (!recv_exact(s, ctx->recv_buf, frame_size))
				break;

			push_frame(ctx, ctx->recv_buf, frame_size);
		}

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

static void *usbcam_create(obs_data_t *settings, obs_source_t *source)
{
	struct usbcam_source *ctx = bzalloc(sizeof(*ctx));
	ctx->source = source;
	ctx->host = bstrdup(obs_data_get_string(settings, "host"));
	ctx->port = (int)obs_data_get_int(settings, "port");
	ctx->reconnect = obs_data_get_bool(settings, "reconnect");
	ctx->reconnect_ms = (int)obs_data_get_int(settings, "reconnect_ms");
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

	/* Stop current connection */
	ctx->running = false;
	pthread_join(ctx->thread, NULL);

	bfree(ctx->host);
	ctx->host = bstrdup(obs_data_get_string(settings, "host"));
	ctx->port = (int)obs_data_get_int(settings, "port");
	ctx->reconnect = obs_data_get_bool(settings, "reconnect");
	ctx->reconnect_ms = (int)obs_data_get_int(settings, "reconnect_ms");

	/* Restart */
	ctx->running = true;
	pthread_create(&ctx->thread, NULL, stream_thread, ctx);
}

static obs_properties_t *usbcam_properties(void *unused)
{
	UNUSED_PARAMETER(unused);
	obs_properties_t *props = obs_properties_create();

	obs_properties_add_text(props, "host",
				"Host (phone IP or 127.0.0.1 for ADB)",
				OBS_TEXT_DEFAULT);
	obs_properties_add_int(props, "port", "Port", 1024, 65535, 1);
	obs_properties_add_bool(props, "reconnect", "Auto-reconnect");
	obs_properties_add_int(props, "reconnect_ms",
			       "Reconnect delay (ms)", 100, 10000, 100);

	return props;
}

static void usbcam_defaults(obs_data_t *settings)
{
	obs_data_set_default_string(settings, "host", "127.0.0.1");
	obs_data_set_default_int(settings, "port", 4748);
	obs_data_set_default_bool(settings, "reconnect", true);
	obs_data_set_default_int(settings, "reconnect_ms", 1000);
}

static struct obs_source_info usbcam_source_info = {
	.id = "android_usbcam",
	.type = OBS_SOURCE_TYPE_INPUT,
	.output_flags = OBS_SOURCE_ASYNC_VIDEO,
	.get_name = usbcam_name,
	.create = usbcam_create,
	.destroy = usbcam_destroy,
	.update = usbcam_update,
	.get_properties = usbcam_properties,
	.get_defaults = usbcam_defaults,
};

/* ── Module entry ─────────────────────────────────────────────────── */

bool obs_module_load(void)
{
	obs_register_source(&usbcam_source_info);
	blog(LOG_INFO, "[android-usbcam] Plugin loaded");
	return true;
}

void obs_module_unload(void)
{
	blog(LOG_INFO, "[android-usbcam] Plugin unloaded");
}
