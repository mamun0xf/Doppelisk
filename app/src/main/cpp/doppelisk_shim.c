/*
 * Doppelisk native shim
 * Copyright 2026 Abdullah Al Mamun
 * SPDX-License-Identifier: Apache-2.0
 *
 * This is the below-Java half of the disguise. Java decides *what* a profile
 * looks like; this library makes the process actually answer that way when the
 * answer comes from libc instead of the framework:
 *
 *   - `__system_property_get` / `_find` / `_read` / `_read_callback`
 *   - `open`, `open64`, `openat`, `openat64`, `__open_2`, `__openat_2`,
 *     `fopen`, `fopen64`, `opendir`
 *   - `stat`, `lstat`, `fstatat`, `access`, `faccessat`, `readlink`,
 *     `readlinkat`
 *   - `uname`, `getifaddrs`, `ioctl(SIOCGIFHWADDR)`
 *   - `glGetString` / `eglQueryString`
 *   - `getsockopt(SO_ORIGINAL_DST)` for the daemon's SOCKS5 exit
 *
 * None of these can be reached from the framework, which is why they exist
 * here. The library never parses profiles: Java compiles a profile into the
 * line protocol documented in `NativeShim.buildConfig` and this file applies it.
 *
 * Hooking strategy
 * ----------------
 * bionic resolves every PLT call through a GOT slot and does not rewrite the
 * GOT after relocation, so a hook is one 8-byte store. `dl_iterate_phdr` walks
 * the modules we are allowed to touch (the target app's own libraries under
 * `/data/app`, `/data/user` or `/data/data`), the `.dynamic` section gives the
 * relocation tables, and each R_AARCH64_JUMP_SLOT / R_AARCH64_GLOB_DAT entry
 * whose symbol name is in `g_targets` is repointed at our wrapper. The original
 * address is kept in `real_*` and called by the wrapper when the profile has
 * nothing to say.
 *
 * `.com.google.android.webview` and `.com.google.android.gms` are skipped on
 * purpose: they are huge, they are loaded early, and patching them buys nothing
 * that the Java layer does not already report correctly.
 *
 * Configuration model
 * -------------------
 * A parsed config is immutable. Refresh installs a new one and publishes it
 * with a release store; hooks take an acquire load and keep using the snapshot
 * they started with. Old snapshots are intentionally not freed — an in-flight
 * hook may still be reading one, and updates are rare enough that the leak is
 * bounded by the number of profile edits in a process lifetime.
 */

#define _GNU_SOURCE 1

#include <arpa/inet.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <ifaddrs.h>
#include <jni.h>
#include <link.h>
#include <netinet/in.h>
#include <netpacket/packet.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/utsname.h>
#include <unistd.h>

#if defined(__ANDROID__)
#include <android/log.h>
#include <sys/system_properties.h>
#define DL_SC_PAGESIZE _SC_PAGESIZE
#define DL_ERRNO() (errno)
#else
/*
 * Some builds happen with a plain Linux cross compiler instead of the NDK
 * (there is no linux-aarch64 NDK toolchain). The ABI is still bionic: we link
 * against the device's own libc/liblog and address symbols by their bionic
 * names. Only the declarations are borrowed from the host headers.
 */
extern int *__errno(void);
#define DL_ERRNO() (*__errno())

#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
extern int __android_log_print(int prio, const char *tag, const char *fmt, ...);

typedef struct prop_info prop_info;
extern int __system_property_get(const char *name, char *value);
extern const prop_info *__system_property_find(const char *name);
extern int __system_property_read(const prop_info *pi, char *name, char *value);
extern void __system_property_read_callback(
    const prop_info *pi,
    void (*callback)(void *cookie, const char *name, const char *value, uint32_t serial),
    void *cookie);

/*
 * bionic numbers `_SC_PAGESIZE` as 39, while the host headers this file is
 * compiled with would say 30. The value is hardcoded rather than read from the
 * host enum, because the library always runs on bionic.
 */
#define DL_SC_PAGESIZE 39
#endif

#ifndef SYS_memfd_create
#error "SYS_memfd_create is required"
#endif

/* bionic constants that are not worth pulling the whole header in for. */
#define DL_ENOENT 2
#define DL_EINTR 4
#define DL_O_CLOEXEC 02000000 /* 0x80000, same on glibc and bionic */
#define DL_SOL_IP 0
#define DL_SOL_IPV6 41
#define DL_SO_ORIGINAL_DST 80
#define DL_IP6T_SO_ORIGINAL_DST 80
#define DL_ARPHRD_ETHER 1
#define DL_SIOCGIFHWADDR 0x8927

#define DL_PROP_NAME_MAX 32
#define DL_PROP_VALUE_MAX 92

#define DL_MAX_PROPS 512
#define DL_MAX_HIDDEN 160
#define DL_MAX_FILES 256
#define DL_MAX_REPLACEMENTS 32
#define DL_MAX_FALLBACKS 8
#define DL_PROP_MAP_SLOTS 512

#define DL_UNAME_NODE_MAX 64
#define DL_UNAME_RELEASE_MAX 64
#define DL_UNAME_VERSION_MAX 64
#define DL_UNAME_MACHINE_MAX 32
#define DL_GL_MAX 192
#define DL_MAX_READ (4 * 1024 * 1024)
#define DL_NEWLINE "\n"

#define DL_TAG "DoppeliskShim"

typedef struct {
    char *key;
    char *value;
} prop_entry;

typedef struct {
    char *path;
    char *content;
} file_entry;

typedef struct {
    char *path;
    char *key;
    char *value;
} replacement_entry;

typedef struct {
    char *path;
    char *body;
} fallback_entry;

/* One immutable snapshot of a compiled profile. */
typedef struct {
    int version;

    size_t prop_count;
    prop_entry props[DL_MAX_PROPS];

    size_t hidden_count;
    char *hidden[DL_MAX_HIDDEN];

    size_t file_count;
    file_entry files[DL_MAX_FILES];

    size_t replacement_count;
    replacement_entry replacements[DL_MAX_REPLACEMENTS];

    size_t fallback_count;
    fallback_entry fallbacks[DL_MAX_FALLBACKS];

    char uname_node[DL_UNAME_NODE_MAX];
    char uname_release[DL_UNAME_RELEASE_MAX];
    char uname_version[DL_UNAME_VERSION_MAX];
    char uname_machine[DL_UNAME_MACHINE_MAX];

    unsigned char mac[6];
    int have_mac;

    char gl_vendor[DL_GL_MAX];
    char gl_renderer[DL_GL_MAX];
    char gl_version[DL_GL_MAX];
    char gl_shader[DL_GL_MAX];
    char egl_vendor[DL_GL_MAX];
    char egl_version[DL_GL_MAX];
} shim_config;

/* ------------------------------------------------------------------ logging */

static void dl_log(int prio, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
#if defined(__ANDROID__)
    __android_log_vprint(prio, DL_TAG, fmt, ap);
#else
    /*
     * A host cross compiler is used for the release build, so `__ANDROID__` is
     * not defined even though the output is linked against bionic and liblog.
     */
    char line[512];
    vsnprintf(line, sizeof line, fmt, ap);
    __android_log_print(prio, DL_TAG, "%s", line);
#endif
    va_end(ap);
}

/* -------------------------------------------------------------------- locks */

/*
 * A spin lock instead of pthread_mutex_t: the critical sections are a handful
 * of pointer copies, and this keeps the library free of pthread dependencies
 * (whose object sizes differ between the host headers and bionic).
 */
static volatile int g_lock;
static volatile int g_patch_lock;
static volatile int g_prop_lock;

static void dl_lock(volatile int *lock) {
    while (__atomic_exchange_n(lock, 1, __ATOMIC_ACQUIRE) != 0) {
        while (__atomic_load_n(lock, __ATOMIC_RELAXED) != 0) {
            /* spin */
        }
    }
}

static void dl_unlock(volatile int *lock) {
    __atomic_store_n(lock, 0, __ATOMIC_RELEASE);
}

/* -------------------------------------------------------------- config glue */

static shim_config *g_config;
static const char *volatile g_patch_filter;

static const shim_config *cfg_load(void) {
    return __atomic_load_n(&g_config, __ATOMIC_ACQUIRE);
}

/* bionic is case-sensitive and so are we: these are the exact prefixes. */
static const char *const k_hidden_property_prefixes[] = {
    "ro.lsposed",
    "persist.lsposed",
    "ro.magisk",
    "persist.magisk",
    "ro.kernelsu",
    "ro.apatch",
    "ro.zygisk",
    "ro.doppelisk",
    "persist.doppelisk",
};

static int prop_prefix_hidden(const char *name) {
    for (size_t i = 0; i < sizeof k_hidden_property_prefixes / sizeof *k_hidden_property_prefixes; i++) {
        const char *prefix = k_hidden_property_prefixes[i];
        if (strncmp(name, prefix, strlen(prefix)) == 0) return 1;
    }
    return 0;
}

/* --------------------------------------------------------------- parsing */

/*
 * Undo the escaping the Kotlin side applies to file bodies. Only `\t`, `\n` and
 * `\\` are special; anything else keeps its backslash verbatim so a Windows-ish
 * path in a fake file survives unchanged.
 */
static char *unescape_config_value(const char *in) {
    size_t len = strlen(in);
    char *out = malloc(len + 1);
    if (!out) return NULL;

    size_t r = 0;
    size_t w = 0;
    while (r < len) {
        char ch = in[r];
        if (ch == '\\' && r + 1 < len) {
            char next = in[r + 1];
            if (next == 'n') {
                out[w++] = '\n';
                r += 2;
                continue;
            }
            if (next == 't') {
                out[w++] = '\t';
                r += 2;
                continue;
            }
            if (next == '\\') {
                out[w++] = '\\';
                r += 2;
                continue;
            }
        }
        out[w++] = ch;
        r++;
    }
    out[w] = '\0';
    return out;
}

static void copy_capped(char *dst, size_t cap, const char *src) {
    if (cap == 0) return;
    size_t len = strlen(src);
    if (len >= cap) len = cap - 1;
    memcpy(dst, src, len);
    dst[len] = '\0';
}

/*
 * `atoi` is deliberately not used: glibc headers rewrite it to the C23
 * `__isoc23_strtol`, a symbol bionic's libc does not export.
 */
static int parse_int(const char *text) {
    int sign = 1;
    int value = 0;

    while (*text == ' ' || *text == '\t') text++;
    if (*text == '-') {
        sign = -1;
        text++;
    } else if (*text == '+') {
        text++;
    }
    while (*text >= '0' && *text <= '9') {
        value = value * 10 + (*text - '0');
        text++;
    }
    return sign * value;
}

static int parse_mac(const char *text, unsigned char out[6]) {
    unsigned values[6];
    int count = 0;
    const char *p = text;

    while (count < 6) {
        unsigned value = 0;
        int digits = 0;
        while (*p && *p != ':') {
            int nibble;
            if (*p >= '0' && *p <= '9') {
                nibble = *p - '0';
            } else if (*p >= 'a' && *p <= 'f') {
                nibble = *p - 'a' + 10;
            } else if (*p >= 'A' && *p <= 'F') {
                nibble = *p - 'A' + 10;
            } else {
                return 0;
            }
            value = (value << 4) | (unsigned)nibble;
            if (++digits > 2) return 0;
            p++;
        }
        if (digits == 0) return 0;
        values[count++] = value;
        if (*p != ':') break;
        p++;
        if (count == 6) return 0;
    }

    if (count != 6 || *p != '\0') return 0;
    for (int i = 0; i < 6; i++) {
        if (values[i] > 0xff) return 0;
        out[i] = (unsigned char)values[i];
    }
    return 1;
}

static void parse_directive(shim_config *cfg, char *line) {
    char *args = strchr(line, '\t');
    if (!args) return; /* every directive carries at least one argument */
    *args++ = '\0';

    switch (line[0]) {
    case 'V':
        cfg->version = parse_int(args);
        break;

    case 'P': {
        if (args[0] == '\0') break;
        char *value = strchr(args, '\t');
        if (value) {
            *value++ = '\0';
            char *end = strchr(value, '\t');
            if (end) *end = '\0';
        }
        if (cfg->prop_count >= DL_MAX_PROPS) {
            dl_log(ANDROID_LOG_WARN, "property table full, dropping %s", args);
            break;
        }
        cfg->props[cfg->prop_count].key = strdup(args);
        cfg->props[cfg->prop_count].value = strdup(value ? value : "");
        if (cfg->props[cfg->prop_count].key && cfg->props[cfg->prop_count].value) {
            cfg->prop_count++;
        } else {
            free(cfg->props[cfg->prop_count].key);
            free(cfg->props[cfg->prop_count].value);
            cfg->props[cfg->prop_count].key = NULL;
            cfg->props[cfg->prop_count].value = NULL;
        }
        break;
    }

    case 'H': {
        char *end = strchr(args, '\t');
        if (end) *end = '\0';
        if (args[0] == '\0') break;
        if (cfg->hidden_count >= DL_MAX_HIDDEN) {
            dl_log(ANDROID_LOG_WARN, "hidden path table full, dropping %s", args);
            break;
        }
        char *copy = strdup(args);
        if (copy) cfg->hidden[cfg->hidden_count++] = copy;
        break;
    }

    case 'F': {
        char *content = strchr(args, '\t');
        if (!content) break;
        *content++ = '\0';
        char *end = strchr(content, '\t');
        if (end) *end = '\0';
        if (args[0] == '\0') break;
        if (cfg->file_count >= DL_MAX_FILES) {
            dl_log(ANDROID_LOG_WARN, "file table full, dropping %s", args);
            break;
        }
        char *path = strdup(args);
        char *body = unescape_config_value(content);
        if (path && body) {
            cfg->files[cfg->file_count].path = path;
            cfg->files[cfg->file_count].content = body;
            cfg->file_count++;
        } else {
            free(path);
            free(body);
        }
        break;
    }

    case 'R': {
        char *body_text = strchr(args, '\t');
        if (!body_text) break;
        *body_text++ = '\0';
        char *end = strchr(body_text, '\t');
        if (end) *end = '\0';
        if (args[0] == '\0') break;
        if (cfg->fallback_count >= DL_MAX_FALLBACKS) {
            dl_log(ANDROID_LOG_WARN, "fallback table full, dropping %s", args);
            break;
        }
        char *path = strdup(args);
        char *body = unescape_config_value(body_text);
        if (path && body) {
            cfg->fallbacks[cfg->fallback_count].path = path;
            cfg->fallbacks[cfg->fallback_count].body = body;
            cfg->fallback_count++;
        } else {
            free(path);
            free(body);
        }
        break;
    }

    case 'X': {
        char *key = strchr(args, '\t');
        if (!key) break;
        *key++ = '\0';
        char *value = strchr(key, '\t');
        if (!value) break;
        *value++ = '\0';
        char *end = strchr(value, '\t');
        if (end) *end = '\0';
        if (args[0] == '\0' || key[0] == '\0') break;
        if (cfg->replacement_count >= DL_MAX_REPLACEMENTS) {
            dl_log(ANDROID_LOG_WARN, "replacement table full, dropping %s", args);
            break;
        }
        char *path = strdup(args);
        char *key_copy = strdup(key);
        char *value_copy = strdup(value);
        if (path && key_copy && value_copy) {
            cfg->replacements[cfg->replacement_count].path = path;
            cfg->replacements[cfg->replacement_count].key = key_copy;
            cfg->replacements[cfg->replacement_count].value = value_copy;
            cfg->replacement_count++;
        } else {
            free(path);
            free(key_copy);
            free(value_copy);
        }
        break;
    }

    case 'M': {
        char *mac = strchr(args, '\t');
        if (!mac) break;
        *mac++ = '\0';
        char *end = strchr(mac, '\t');
        if (end) *end = '\0';
        if (args[0] == '\0') break;
        unsigned char bytes[6];
        if (!parse_mac(mac, bytes)) {
            dl_log(ANDROID_LOG_WARN, "bad MAC address in config: %s", mac);
            break;
        }
        memcpy(cfg->mac, bytes, 6);
        cfg->have_mac = 1;
        break;
    }

    case 'U': {
        char *release = strchr(args, '\t');
        char *version = release ? strchr(release + 1, '\t') : NULL;
        char *machine = version ? strchr(version + 1, '\t') : NULL;
        if (release) *release++ = '\0';
        if (version) *version++ = '\0';
        if (machine) {
            *machine++ = '\0';
            char *end = strchr(machine, '\t');
            if (end) *end = '\0';
        }
        if (args[0] == '\0') break;
        copy_capped(cfg->uname_node, sizeof cfg->uname_node, args);
        if (release) copy_capped(cfg->uname_release, sizeof cfg->uname_release, release);
        if (version) copy_capped(cfg->uname_version, sizeof cfg->uname_version, version);
        if (machine) copy_capped(cfg->uname_machine, sizeof cfg->uname_machine, machine);
        break;
    }

    case 'G': {
        char *value = strchr(args, '\t');
        if (!value) break;
        *value++ = '\0';
        char *end = strchr(value, '\t');
        if (end) *end = '\0';
        if (args[0] == '\0') break;

        char *dest = NULL;
        size_t cap = sizeof cfg->gl_vendor;

        if (strcmp(args, "vendor") == 0) dest = cfg->gl_vendor;
        else if (strcmp(args, "renderer") == 0) dest = cfg->gl_renderer;
        else if (strcmp(args, "version") == 0) dest = cfg->gl_version;
        else if (strcmp(args, "glsl") == 0) dest = cfg->gl_shader;
        else if (strcmp(args, "egl_vendor") == 0) dest = cfg->egl_vendor;
        else if (strcmp(args, "egl_version") == 0) dest = cfg->egl_version;

        if (dest) copy_capped(dest, cap, value);
        break;
    }

    default:
        break;
    }
}

static shim_config *parse_config(const char *text) {
    shim_config *cfg = calloc(1, sizeof *cfg);
    if (!cfg) return NULL;
    if (!text) return cfg;

    const char *cursor = text;
    while (*cursor) {
        const char *newline = strchr(cursor, '\n');
        size_t len = newline ? (size_t)(newline - cursor) : strlen(cursor);
        if (len != 0) {
            char *line = malloc(len + 1);
            if (!line) break;
            memcpy(line, cursor, len);
            line[len] = '\0';
            parse_directive(cfg, line);
            free(line);
        }
        if (!newline) break;
        cursor = newline + 1;
    }

    return cfg;
}

/* ------------------------------------------------------------ file helpers */

typedef struct {
    char *data;
    size_t len;
    size_t cap;
} dl_buffer;

static int buffer_reserve(dl_buffer *buf, size_t extra) {
    if (buf->len + extra <= buf->cap) return 1;
    size_t cap = buf->cap ? buf->cap : 4096;
    while (cap < buf->len + extra) cap *= 2;
    char *grown = realloc(buf->data, cap);
    if (!grown) return 0;
    buf->data = grown;
    buf->cap = cap;
    return 1;
}

static int buffer_append(dl_buffer *buf, const char *data, size_t len) {
    if (!buffer_reserve(buf, len + 1)) return 0;
    memcpy(buf->data + buf->len, data, len);
    buf->len += len;
    buf->data[buf->len] = '\0';
    return 1;
}

/*
 * A read-only replacement is handed back as a sealed memfd, so the caller gets
 * a normal file descriptor and nothing needs to know that the bytes are ours.
 */
static int serve_buffer(const char *data, size_t len) {
    int fd = (int)syscall(SYS_memfd_create, "doppelisk-file", 1 /* MFD_CLOEXEC */);
    if (fd < 0) return -1;

    size_t written = 0;
    while (written < len) {
        ssize_t n = write(fd, data + written, len - written);
        if (n < 0) {
            if (DL_ERRNO() == DL_EINTR) continue;
            close(fd);
            return -1;
        }
        written += (size_t)n;
    }
    lseek(fd, 0, SEEK_SET);
    return fd;
}

static char *read_all(int fd, size_t *out_len) {
    dl_buffer buf = {0};
    for (;;) {
        /*
         * The original capped this at 4 MiB and so do we: the hook runs inside
         * a target app on a device where a stray multi-gigabyte read would be
         * an OOM kill, not a failed read.
         */
        if (buf.len >= DL_MAX_READ) break;
        if (!buffer_reserve(&buf, 64 * 1024)) {
            free(buf.data);
            return NULL;
        }
        size_t room = buf.cap - buf.len;
        if (room > DL_MAX_READ - buf.len) room = DL_MAX_READ - buf.len;
        ssize_t n = read(fd, buf.data + buf.len, room);
        if (n < 0) {
            if (DL_ERRNO() == DL_EINTR) continue;
            free(buf.data);
            return NULL;
        }
        if (n == 0) break;
        buf.len += (size_t)n;
    }
    if (!buf.data) {
        /* Empty file: still hand back a valid, empty string. */
        buf.data = calloc(1, 1);
        if (!buf.data) return NULL;
    }
    buf.data[buf.len] = '\0';
    *out_len = buf.len;
    return buf.data;
}

static const replacement_entry *find_replacement(const shim_config *cfg, const char *path, const char *key, size_t key_len) {
    if (!cfg) return NULL;
    for (size_t i = 0; i < cfg->replacement_count; i++) {
        const replacement_entry *entry = &cfg->replacements[i];
        if (!entry->path || strcmp(entry->path, path) != 0) continue;
        if (strlen(entry->key) != key_len) continue;
        if (memcmp(entry->key, key, key_len) != 0) continue;
        return entry;
    }
    return NULL;
}

/*
 * Rewrite `KEY=value` lines. Everything outside the key is taken from the real
 * file, so a profile only has to list the lines that would betray it.
 */
static char *apply_replacements(const shim_config *cfg, const char *path, const char *data, size_t len, size_t *out_len) {
    dl_buffer out = {0};
    size_t start = 0;

    while (start < len) {
        const char *line = data + start;
        const char *newline = memchr(line, '\n', len - start);
        size_t line_len = newline ? (size_t)(newline - line) : len - start;
        size_t step = newline ? line_len + 1 : line_len;

        const char *eq = memchr(line, '=', line_len);
        const replacement_entry *entry = NULL;
        if (eq) entry = find_replacement(cfg, path, line, (size_t)(eq - line));

        if (entry) {
            if (!buffer_append(&out, entry->key, strlen(entry->key)) ||
                !buffer_append(&out, "=", 1) ||
                !buffer_append(&out, entry->value, strlen(entry->value))) {
                free(out.data);
                return NULL;
            }
        } else if (!buffer_append(&out, line, line_len)) {
            free(out.data);
            return NULL;
        }

        if (newline && !buffer_append(&out, "\n", 1)) {
            free(out.data);
            return NULL;
        }
        start += step;
    }

    if (!out.data) {
        out.data = calloc(1, 1);
        if (!out.data) return NULL;
    }
    *out_len = out.len;
    return out.data;
}

static const file_entry *find_file(const shim_config *cfg, const char *path) {
    if (!cfg) return NULL;
    for (size_t i = 0; i < cfg->file_count; i++) {
        const file_entry *entry = &cfg->files[i];
        if (entry->path && strcmp(entry->path, path) == 0) return entry;
    }
    return NULL;
}

static const fallback_entry *find_fallback(const shim_config *cfg, const char *path) {
    if (!cfg) return NULL;
    for (size_t i = 0; i < cfg->fallback_count; i++) {
        const fallback_entry *entry = &cfg->fallbacks[i];
        if (entry->path && strcmp(entry->path, path) == 0) return entry;
    }
    return NULL;
}

/* The lines that reveal a rooted device inside /proc/<pid>/{mounts,maps,...}. */
static const char *const k_proc_markers[] = {
    "magisk", "Magisk",
    "kernelsu", "KernelSU",
    "apatch", "APatch",
    "lsposed", "LSPosed",
    "lspd",
    "zygisk", "Zygisk",
    "doppelisk",
    "riru", "Riru",
    "/data/adb",
    "/sbin/.magisk",
    "/debug_ramdisk",
    "/system/bin/su",
    "/system/xbin/su",
    "/su/bin/su",
};

static int mem_contains(const char *haystack, size_t haystack_len, const char *needle) {
    size_t needle_len = strlen(needle);
    if (needle_len == 0 || needle_len > haystack_len) return 0;
    const char *end = haystack + (haystack_len - needle_len);
    for (const char *p = haystack; p <= end; p++) {
        if (*p == needle[0] && memcmp(p, needle, needle_len) == 0) return 1;
    }
    return 0;
}

static int line_has_marker(const char *line, size_t len) {
    for (size_t i = 0; i < sizeof k_proc_markers / sizeof *k_proc_markers; i++) {
        if (mem_contains(line, len, k_proc_markers[i])) return 1;
    }
    return 0;
}

/* --------------------------------------------------------- real functions */

static int (*real_system_property_get_fn)(const char *, char *);
static const void *(*real_system_property_find_fn)(const char *);
static int (*real_system_property_read_fn)(const void *, char *, char *);
static void (*real_system_property_read_callback_fn)(const void *, void (*)(void *, const char *, const char *, uint32_t), void *);

static int (*real_open_fn)(const char *, int, ...);
static int (*real_open64_fn)(const char *, int, ...);
static int (*real_openat_fn)(int, const char *, int, ...);
static int (*real_openat64_fn)(int, const char *, int, ...);
static int (*real___open_2_fn)(const char *, int);
static int (*real___openat_2_fn)(int, const char *, int);
static FILE *(*real_fopen_fn)(const char *, const char *);
static FILE *(*real_fopen64_fn)(const char *, const char *);
static void *(*real_opendir_fn)(const char *);

static int (*real_stat_fn)(const char *, void *);
static int (*real_lstat_fn)(const char *, void *);
static int (*real_fstatat_fn)(int, const char *, void *, int);
static int (*real_access_fn)(const char *, int);
static int (*real_faccessat_fn)(int, const char *, int, int);
static ssize_t (*real_readlink_fn)(const char *, char *, size_t);
static ssize_t (*real_readlinkat_fn)(int, const char *, char *, size_t);

static int (*real_uname_fn)(struct utsname *);
static int (*real_getifaddrs_fn)(struct ifaddrs **);
static int (*real_ioctl_fn)(int, unsigned long, void *);

static const unsigned char *(*real_glGetString_fn)(unsigned int);
static const unsigned char *(*real_eglQueryString_fn)(void *, int);

/*
 * The wrappers are defined below the table, so the table needs prototypes.
 * Only the exact 25 symbols the installed base depends on are listed; adding
 * more would change which relocations the shim touches, and `stat64` and
 * friends are not separate symbols in bionic's public surface anyway.
 */
static int my_system_property_get(const char *name, char *value);
static const void *my_system_property_find(const char *name);
static int my_system_property_read(const void *pi, char *name, char *value);
static void my_system_property_read_callback(
    const void *pi,
    void (*callback)(void *cookie, const char *name, const char *value, uint32_t serial),
    void *cookie);

static int my_open(const char *path, int flags, ...);
static int my_open64(const char *path, int flags, ...);
static int my_openat(int dirfd, const char *path, int flags, ...);
static int my_openat64(int dirfd, const char *path, int flags, ...);
static int my___open_2(const char *path, int flags);
static int my___openat_2(int dirfd, const char *path, int flags);
static FILE *my_fopen(const char *path, const char *mode);
static FILE *my_fopen64(const char *path, const char *mode);
static void *my_opendir(const char *path);

static int my_stat(const char *path, void *buf);
static int my_lstat(const char *path, void *buf);
static int my_fstatat(int dirfd, const char *path, void *buf, int flags);
static int my_access(const char *path, int mode);
static int my_faccessat(int dirfd, const char *path, int mode, int flags);
static ssize_t my_readlink(const char *path, char *buf, size_t size);
static ssize_t my_readlinkat(int dirfd, const char *path, char *buf, size_t size);

static int my_uname(struct utsname *u);
static int my_getifaddrs(struct ifaddrs **ifap);
static int my_ioctl(int fd, unsigned long request, void *arg);

static const unsigned char *my_glGetString(unsigned int name);
static const unsigned char *my_eglQueryString(void *display, int name);

typedef struct {
    const char *name;
    void *hook;
    void **real_slot;
    int optional; /* missing symbols are only worth a log line when required */
    int resolved;
} shim_target;

static shim_target g_targets[] = {
    {"__system_property_get", (void *)my_system_property_get, (void **)&real_system_property_get_fn, 0, 0},
    {"__system_property_find", (void *)my_system_property_find, (void **)&real_system_property_find_fn, 0, 0},
    {"__system_property_read", (void *)my_system_property_read, (void **)&real_system_property_read_fn, 1, 0},
    {"__system_property_read_callback", (void *)my_system_property_read_callback, (void **)&real_system_property_read_callback_fn, 1, 0},

    {"open", (void *)my_open, (void **)&real_open_fn, 0, 0},
    {"open64", (void *)my_open64, (void **)&real_open64_fn, 1, 0},
    {"openat", (void *)my_openat, (void **)&real_openat_fn, 0, 0},
    {"openat64", (void *)my_openat64, (void **)&real_openat64_fn, 1, 0},
    {"__open_2", (void *)my___open_2, (void **)&real___open_2_fn, 1, 0},
    {"__openat_2", (void *)my___openat_2, (void **)&real___openat_2_fn, 1, 0},
    {"fopen", (void *)my_fopen, (void **)&real_fopen_fn, 0, 0},
    {"fopen64", (void *)my_fopen64, (void **)&real_fopen64_fn, 1, 0},
    {"opendir", (void *)my_opendir, (void **)&real_opendir_fn, 0, 0},

    {"stat", (void *)my_stat, (void **)&real_stat_fn, 0, 0},
    {"lstat", (void *)my_lstat, (void **)&real_lstat_fn, 0, 0},
    {"fstatat", (void *)my_fstatat, (void **)&real_fstatat_fn, 0, 0},
    {"access", (void *)my_access, (void **)&real_access_fn, 0, 0},
    {"faccessat", (void *)my_faccessat, (void **)&real_faccessat_fn, 0, 0},
    {"readlink", (void *)my_readlink, (void **)&real_readlink_fn, 0, 0},
    {"readlinkat", (void *)my_readlinkat, (void **)&real_readlinkat_fn, 0, 0},

    {"uname", (void *)my_uname, (void **)&real_uname_fn, 0, 0},
    {"getifaddrs", (void *)my_getifaddrs, (void **)&real_getifaddrs_fn, 0, 0},
    {"ioctl", (void *)my_ioctl, (void **)&real_ioctl_fn, 0, 0},

    {"glGetString", (void *)my_glGetString, (void **)&real_glGetString_fn, 1, 0},
    {"eglQueryString", (void *)my_eglQueryString, (void **)&real_eglQueryString_fn, 1, 0},
};

#define DL_TARGET_COUNT (sizeof g_targets / sizeof g_targets[0])

/* ------------------------------------------------------------ prop hooks */

typedef struct {
    const void *pi;
    char name[DL_PROP_NAME_MAX];
    char value[DL_PROP_VALUE_MAX];
} prop_map_slot;

static prop_map_slot g_prop_map[DL_PROP_MAP_SLOTS];
static unsigned g_prop_map_next;

static void prop_map_record(const void *pi, const char *name, const char *value) {
    dl_lock(&g_prop_lock);
    prop_map_slot *slot = &g_prop_map[g_prop_map_next % DL_PROP_MAP_SLOTS];
    g_prop_map_next++;
    slot->pi = pi;
    copy_capped(slot->name, sizeof slot->name, name);
    copy_capped(slot->value, sizeof slot->value, value);
    dl_unlock(&g_prop_lock);
}

static prop_map_slot *prop_map_lookup(const void *pi) {
    dl_lock(&g_prop_lock);
    for (size_t i = 0; i < DL_PROP_MAP_SLOTS; i++) {
        if (g_prop_map[i].pi == pi) {
            dl_unlock(&g_prop_lock);
            return &g_prop_map[i];
        }
    }
    dl_unlock(&g_prop_lock);
    return NULL;
}

static const char *config_property(const shim_config *cfg, const char *name) {
    if (!cfg) return NULL;
    for (size_t i = 0; i < cfg->prop_count; i++) {
        if (cfg->props[i].key && strcmp(cfg->props[i].key, name) == 0) return cfg->props[i].value;
    }
    return NULL;
}

static int my_system_property_get(const char *name, char *value) {
    const shim_config *cfg = cfg_load();
    if (!name || !value || !cfg) {
        return real_system_property_get_fn ? real_system_property_get_fn(name, value) : 0;
    }

    const char *spoofed = config_property(cfg, name);
    if (spoofed) {
        copy_capped(value, DL_PROP_VALUE_MAX, spoofed);
        return (int)strlen(value);
    }

    if (prop_prefix_hidden(name)) {
        value[0] = '\0';
        return 0;
    }

    return real_system_property_get_fn ? real_system_property_get_fn(name, value) : 0;
}

static const void *my_system_property_find(const char *name) {
    if (!name) return real_system_property_find_fn ? real_system_property_find_fn(name) : NULL;
    if (prop_prefix_hidden(name)) return NULL;

    const void *pi = real_system_property_find_fn ? real_system_property_find_fn(name) : NULL;
    const shim_config *cfg = cfg_load();
    const char *spoofed = config_property(cfg, name);
    if (pi && spoofed) prop_map_record(pi, name, spoofed);
    return pi;
}

static int my_system_property_read(const void *pi, char *name, char *value) {
    prop_map_slot *slot = pi ? prop_map_lookup(pi) : NULL;
    if (slot) {
        if (name) copy_capped(name, DL_PROP_NAME_MAX, slot->name);
        if (value) copy_capped(value, DL_PROP_VALUE_MAX, slot->value);
        return 0;
    }
    return real_system_property_read_fn ? real_system_property_read_fn(pi, name, value) : -1;
}

static void my_system_property_read_callback(
    const void *pi,
    void (*callback)(void *cookie, const char *name, const char *value, uint32_t serial),
    void *cookie) {
    prop_map_slot *slot = pi ? prop_map_lookup(pi) : NULL;
    if (slot) {
        callback(cookie, slot->name, slot->value, 0);
        return;
    }
    if (real_system_property_read_callback_fn) {
        real_system_property_read_callback_fn(pi, callback, cookie);
    }
}

/* ------------------------------------------------------------ file hooks */

static int path_hidden(const shim_config *cfg, const char *path) {
    if (!cfg || !path || !*path) return 0;
    for (size_t i = 0; i < cfg->hidden_count; i++) {
        const char *hidden = cfg->hidden[i];
        if (hidden && *hidden && strstr(path, hidden)) return 1;
    }
    return 0;
}

static int proc_view_name(const char *path) {
    static const char *const exact[] = {
        "/proc/mounts",
        "/proc/self/mounts",
        "/proc/thread-self/mounts",
        "/proc/self/mountinfo",
        "/proc/self/maps",
        "/proc/self/smaps",
    };
    for (size_t i = 0; i < sizeof exact / sizeof *exact; i++) {
        if (strcmp(path, exact[i]) == 0) return 1;
    }

    if (strncmp(path, "/proc/", 6) != 0) return 0;
    const char *p = path + 6;
    const char *digits = p;
    while (*p >= '0' && *p <= '9') p++;
    if (p == digits || *p != '/') return 0;
    const char *leaf = p + 1;
    return strcmp(leaf, "mounts") == 0 ||
           strcmp(leaf, "mountinfo") == 0 ||
           strcmp(leaf, "maps") == 0 ||
           strcmp(leaf, "smaps") == 0;
}

/*
 * Serve a /proc file with the giveaway lines removed. The file is small and
 * read once, so a memfd is both cheaper and more transparent than trying to
 * emulate a directory.
 */
static int make_proc_view(const char *path) {
    if (!real_open_fn) return -1;
    int fd = real_open_fn(path, O_RDONLY | DL_O_CLOEXEC, 0);
    if (fd < 0) return -1;

    size_t len = 0;
    char *data = read_all(fd, &len);
    close(fd);
    if (!data) return -1;

    dl_buffer out = {0};
    size_t start = 0;
    while (start < len) {
        const char *line = data + start;
        const char *newline = memchr(line, '\n', len - start);
        size_t line_len = newline ? (size_t)(newline - line) : len - start;
        size_t step = newline ? line_len + 1 : line_len;

        if (!line_has_marker(line, line_len)) {
            if (!buffer_append(&out, line, line_len) || (newline && !buffer_append(&out, "\n", 1))) {
                free(data);
                free(out.data);
                return -1;
            }
        }
        start += step;
    }
    free(data);

    int result = serve_buffer(out.data, out.len);
    free(out.data);
    return result;
}

static int fail_enoent(void) {
    DL_ERRNO() = DL_ENOENT;
    return -1;
}

/*
 * Decide what `path` should read as.
 *
 * Two independent mechanisms, checked in this order because they are used for
 * two different kinds of file:
 *
 *  1. `X` entries. These files are mostly real, so read the real bytes and
 *     rewrite only the listed `KEY=value` lines. When the real file is absent
 *     the `R` body stands in for it, still passed through the same rewrite.
 *  2. `F` entries. The body is fully synthesised and the real file is never
 *     opened — `/proc/cpuinfo` must not leak a single real line.
 *
 * A path with an `X` entry is therefore never answered from an `F` entry: only
 * a failed read and a missing `R` fall through, matching the published
 * protocol.
 */
static int read_override(const shim_config *cfg, const char *path) {
    if (!cfg || !path) return -1;

    int has_replacement = 0;
    for (size_t i = 0; i < cfg->replacement_count; i++) {
        const replacement_entry *entry = &cfg->replacements[i];
        if (entry->path && strcmp(entry->path, path) == 0) {
            has_replacement = 1;
            break;
        }
    }

    if (has_replacement) {
        char *data = NULL;
        size_t len = 0;

        int fd = real_open_fn ? real_open_fn(path, O_RDONLY | DL_O_CLOEXEC, 0) : -1;
        if (fd >= 0) {
            data = read_all(fd, &len);
            close(fd);
        } else {
            /*
             * `uevent` exists on some kernels and not others; the `R` body is
             * the stand-in for the ones where it does not.
             */
            const fallback_entry *fallback = find_fallback(cfg, path);
            if (fallback && fallback->body) {
                data = strdup(fallback->body);
                if (data) len = strlen(data);
            }
        }

        if (data) {
            size_t out_len = 0;
            char *body = apply_replacements(cfg, path, data, len, &out_len);
            free(data);
            if (body) {
                int out = serve_buffer(body, out_len);
                free(body);
                if (out >= 0) return out;
            }
        }
    }

    const file_entry *file = find_file(cfg, path);
    if (file) {
        if (!file->content) return -1;
        return serve_buffer(file->content, strlen(file->content));
    }
    return -1;
}

static int wrap_open(const char *path, int flags, int mode) {
    const shim_config *cfg = cfg_load();

    if (!path) return real_open_fn(path, flags, mode);

    if ((flags & O_CREAT) != 0 || (flags & O_ACCMODE) != O_RDONLY) {
        if (path_hidden(cfg, path)) return fail_enoent();
        return real_open_fn(path, flags, mode);
    }

    int fd = read_override(cfg, path);
    if (fd >= 0) return fd;
    if (path_hidden(cfg, path)) return fail_enoent();
    if (proc_view_name(path)) {
        fd = make_proc_view(path);
        if (fd >= 0) return fd;
    }
    return real_open_fn(path, flags, mode);
}

static int my_open(const char *path, int flags, ...) {
    int mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, int);
        va_end(ap);
    }
    return wrap_open(path, flags, mode);
}

static int my_open64(const char *path, int flags, ...) {
    int mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, int);
        va_end(ap);
    }
    if (!real_open64_fn) return real_open_fn(path, flags, mode);

    const shim_config *cfg = cfg_load();
    if (!path) return real_open64_fn(path, flags, mode);
    if ((flags & O_CREAT) != 0 || (flags & O_ACCMODE) != O_RDONLY) {
        if (path_hidden(cfg, path)) return fail_enoent();
        return real_open64_fn(path, flags, mode);
    }

    int fd = read_override(cfg, path);
    if (fd >= 0) return fd;
    if (path_hidden(cfg, path)) return fail_enoent();
    if (proc_view_name(path)) {
        fd = make_proc_view(path);
        if (fd >= 0) return fd;
    }
    return real_open64_fn(path, flags, mode);
}

static int wrap_openat(int (*real_fn)(int, const char *, int, ...), int dirfd, const char *path, int flags, int mode) {
    const shim_config *cfg = cfg_load();

    if (!path) return real_fn(dirfd, path, flags, mode);
    if ((flags & O_CREAT) != 0 || (flags & O_ACCMODE) != O_RDONLY) {
        if (path_hidden(cfg, path)) return fail_enoent();
        return real_fn(dirfd, path, flags, mode);
    }

    int fd = read_override(cfg, path);
    if (fd >= 0) return fd;
    if (path_hidden(cfg, path)) return fail_enoent();
    if (proc_view_name(path)) {
        fd = make_proc_view(path);
        if (fd >= 0) return fd;
    }
    return real_fn(dirfd, path, flags, mode);
}

static int my_openat(int dirfd, const char *path, int flags, ...) {
    int mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, int);
        va_end(ap);
    }
    return wrap_openat(real_openat_fn, dirfd, path, flags, mode);
}

static int my_openat64(int dirfd, const char *path, int flags, ...) {
    int mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, int);
        va_end(ap);
    }
    if (!real_openat64_fn) return wrap_openat(real_openat_fn, dirfd, path, flags, mode);
    return wrap_openat(real_openat64_fn, dirfd, path, flags, mode);
}

static int my___open_2(const char *path, int flags) {
    const shim_config *cfg = cfg_load();
    int fd = read_override(cfg, path);
    if (fd >= 0) return fd;
    if (path_hidden(cfg, path)) return fail_enoent();
    return real___open_2_fn(path, flags);
}

static int my___openat_2(int dirfd, const char *path, int flags) {
    const shim_config *cfg = cfg_load();
    int fd = read_override(cfg, path);
    if (fd >= 0) return fd;
    if (path_hidden(cfg, path)) return fail_enoent();
    return real___openat_2_fn(dirfd, path, flags);
}

static FILE *wrap_fopen(FILE *(*real_fn)(const char *, const char *), const char *path, const char *mode) {
    const shim_config *cfg = cfg_load();
    if (!path || !mode) return real_fn(path, mode);

    int reading = mode[0] == 'r';
    if (reading) {
        int fd = read_override(cfg, path);
        if (fd >= 0) {
            FILE *stream = fdopen(fd, mode);
            if (stream) return stream;
            close(fd);
        }
    }

    if (path_hidden(cfg, path)) {
        DL_ERRNO() = DL_ENOENT;
        return NULL;
    }

    if (reading && proc_view_name(path)) {
        int fd = make_proc_view(path);
        if (fd >= 0) {
            FILE *stream = fdopen(fd, mode);
            if (stream) return stream;
            close(fd);
        }
    }
    return real_fn(path, mode);
}

static FILE *my_fopen(const char *path, const char *mode) {
    return wrap_fopen(real_fopen_fn, path, mode);
}

static FILE *my_fopen64(const char *path, const char *mode) {
    return wrap_fopen(real_fopen64_fn ? real_fopen64_fn : real_fopen_fn, path, mode);
}

static void *my_opendir(const char *path) {
    if (path_hidden(cfg_load(), path)) {
        DL_ERRNO() = DL_ENOENT;
        return NULL;
    }
    return real_opendir_fn(path);
}

static int my_stat(const char *path, void *buf) {
    if (path_hidden(cfg_load(), path)) return fail_enoent();
    return real_stat_fn(path, buf);
}

static int my_lstat(const char *path, void *buf) {
    if (path_hidden(cfg_load(), path)) return fail_enoent();
    return real_lstat_fn(path, buf);
}

static int my_fstatat(int dirfd, const char *path, void *buf, int flags) {
    if (path_hidden(cfg_load(), path)) return fail_enoent();
    return real_fstatat_fn(dirfd, path, buf, flags);
}

static int my_access(const char *path, int mode) {
    if (path_hidden(cfg_load(), path)) return fail_enoent();
    return real_access_fn(path, mode);
}

static int my_faccessat(int dirfd, const char *path, int mode, int flags) {
    if (path_hidden(cfg_load(), path)) return fail_enoent();
    return real_faccessat_fn(dirfd, path, mode, flags);
}

static ssize_t my_readlink(const char *path, char *buf, size_t size) {
    if (path_hidden(cfg_load(), path)) return fail_enoent();
    return real_readlink_fn(path, buf, size);
}

static ssize_t my_readlinkat(int dirfd, const char *path, char *buf, size_t size) {
    if (path_hidden(cfg_load(), path)) return fail_enoent();
    return real_readlinkat_fn(dirfd, path, buf, size);
}

static int my_uname(struct utsname *u) {
    int rc = real_uname_fn(u);
    const shim_config *cfg = cfg_load();
    if (rc != 0 || !u || !cfg) return rc;

    if (cfg->uname_node[0]) copy_capped(u->nodename, sizeof u->nodename, cfg->uname_node);
    if (cfg->uname_release[0]) copy_capped(u->release, sizeof u->release, cfg->uname_release);
    if (cfg->uname_version[0]) copy_capped(u->version, sizeof u->version, cfg->uname_version);
    if (cfg->uname_machine[0]) copy_capped(u->machine, sizeof u->machine, cfg->uname_machine);
    return rc;
}

/*
 * Only the Wi-Fi interface is spoofed. The `M` directive carries an interface
 * name, but the wire protocol renders it for readability; wireless interfaces
 * all share the `wlan` prefix on Android.
 */
static int iface_matches(const char *iface) {
    return iface != NULL && strncmp(iface, "wlan", 4) == 0;
}

static int my_ioctl(int fd, unsigned long request, void *arg) {
    if (arg && request == DL_SIOCGIFHWADDR) {
        struct {
            char name[16];
            struct sockaddr addr;
        } *ifreq = arg;

        if (iface_matches(ifreq->name)) {
            int rc = real_ioctl_fn(fd, request, arg);
            const shim_config *cfg = cfg_load();
            if (rc == 0 && cfg && cfg->have_mac) {
                ifreq->addr.sa_family = DL_ARPHRD_ETHER;
                memcpy(ifreq->addr.sa_data, cfg->mac, 6);
            }
            return rc;
        }
    }
    return real_ioctl_fn(fd, request, arg);
}

static int my_getifaddrs(struct ifaddrs **ifap) {
    int rc = real_getifaddrs_fn(ifap);
    const shim_config *cfg = cfg_load();
    if (rc != 0 || !ifap || !cfg || !cfg->have_mac) return rc;

    for (struct ifaddrs *ifa = *ifap; ifa; ifa = ifa->ifa_next) {
        struct sockaddr_ll *ll = (struct sockaddr_ll *)ifa->ifa_addr;
        if (!ll || !ifa->ifa_name) continue;
        if (ll->sll_family != AF_PACKET) continue;
        if (!iface_matches(ifa->ifa_name)) continue;
        if (ll->sll_halen <= 5) continue;
        memcpy(ll->sll_addr, cfg->mac, 6);
    }
    return rc;
}

static const unsigned char *my_glGetString(unsigned int name) {
    const shim_config *cfg = cfg_load();
    if (cfg) {
        const char *value = NULL;
        switch (name) {
        case 0x1F00: value = cfg->gl_vendor; break;   /* GL_VENDOR */
        case 0x1F01: value = cfg->gl_renderer; break; /* GL_RENDERER */
        case 0x1F02: value = cfg->gl_version; break;  /* GL_VERSION */
        case 0x8B8C: value = cfg->gl_shader; break;   /* GL_SHADING_LANGUAGE_VERSION */
        default: break;
        }
        if (value && *value) return (const unsigned char *)value;
    }
    return real_glGetString_fn(name);
}

static const unsigned char *my_eglQueryString(void *display, int name) {
    const shim_config *cfg = cfg_load();
    if (cfg) {
        const char *value = NULL;
        switch (name) {
        case 0x3053: value = cfg->egl_vendor; break;  /* EGL_VENDOR */
        case 0x3054: value = cfg->egl_version; break; /* EGL_VERSION */
        default: break;
        }
        if (value && *value) return (const unsigned char *)value;
    }
    return real_eglQueryString_fn(display, name);
}

/* ------------------------------------------------------------- GOT patch */

#define DL_R_AARCH64_JUMP_SLOT 1026
#define DL_R_AARCH64_GLOB_DAT 1025
#ifndef PT_GNU_RELRO
#define PT_GNU_RELRO 0x6474e552
#endif

typedef struct {
    ElfW(Addr) base;
    const ElfW(Phdr) *phdr;
    ElfW(Half) phnum;
    const ElfW(Sym) *symtab;
    const char *strtab;
    const char *name;
} reloc_plan;

/*
 * What the page used to allow, derived from the program headers. The GOT of a
 * RELRO library is read-only after relocation, so writing the slot needs a
 * temporary RW mapping and then the previous protection back.
 */
static int page_protection(const reloc_plan *plan, ElfW(Addr) address) {
    int prot = PROT_READ | PROT_WRITE;
    int found = 0;

    for (ElfW(Half) i = 0; i < plan->phnum; i++) {
        const ElfW(Phdr) *ph = &plan->phdr[i];
        if (ph->p_type != PT_LOAD) continue;
        if (address < ph->p_vaddr || address >= ph->p_vaddr + ph->p_memsz) continue;

        prot = 0;
        if (ph->p_flags & PF_R) prot |= PROT_READ;
        if (ph->p_flags & PF_W) prot |= PROT_WRITE;
        if (ph->p_flags & PF_X) prot |= PROT_EXEC;
        found = 1;
        break;
    }

    if (found) {
        for (ElfW(Half) i = 0; i < plan->phnum; i++) {
            const ElfW(Phdr) *ph = &plan->phdr[i];
            if (ph->p_type != PT_GNU_RELRO) continue;
            if (address < ph->p_vaddr || address >= ph->p_vaddr + ph->p_memsz) continue;
            prot &= ~PROT_WRITE;
            break;
        }
    }
    return prot;
}

static int patch_table(const reloc_plan *plan, const void *relocations, size_t size, int is_rela) {
    size_t entry_size = is_rela ? sizeof(ElfW(Rela)) : sizeof(ElfW(Rel));
    if (!relocations || size < entry_size) return 0;

    long page_size = sysconf(DL_SC_PAGESIZE);
    if (page_size <= 0) page_size = 4096;

    size_t count = size / entry_size;
    int patched = 0;

    for (size_t i = 0; i < count; i++) {
        ElfW(Addr) offset;
        ElfW(Xword) info;

        if (is_rela) {
            const ElfW(Rela) *rel = &((const ElfW(Rela) *)relocations)[i];
            offset = rel->r_offset;
            info = rel->r_info;
        } else {
            const ElfW(Rel) *rel = &((const ElfW(Rel) *)relocations)[i];
            offset = rel->r_offset;
            info = rel->r_info;
        }

        uint32_t type = (uint32_t)info;
        if (type != DL_R_AARCH64_JUMP_SLOT && type != DL_R_AARCH64_GLOB_DAT) continue;

        uint32_t symbol = (uint32_t)(info >> 32);
        const char *name = plan->strtab + plan->symtab[symbol].st_name;
        if (!name || !*name) continue;

        for (size_t t = 0; t < DL_TARGET_COUNT; t++) {
            shim_target *target = &g_targets[t];
            if (!target->hook) continue;
            if (strcmp(target->name, name) != 0) continue;

            void **slot = (void **)(plan->base + offset);

            /*
             * `dlsym(RTLD_DEFAULT, ...)` cannot see symbols that were loaded
             * into a private linker namespace, and on Android that is every
             * library an app loads, `libGLESv2.so` and `libEGL.so` included.
             * The slot about to be replaced already holds the address the
             * linker picked, so take the real function from there instead.
             */
            if (!target->resolved) {
                void *real = *slot;
                if (!real) break;

                /*
                 * A lazily bound slot still points at this module's own PLT
                 * stub, which jumps through the very slot we are about to
                 * overwrite. Accept only addresses that live somewhere else.
                 */
                Dl_info origin;
                if (dladdr(real, &origin) == 0 || !origin.dli_fname ||
                    strcmp(origin.dli_fname, plan->name) == 0) {
                    break;
                }

                *target->real_slot = real;
                target->resolved = 1;
            }

            if (*slot == target->hook) break;

            uintptr_t page = (uintptr_t)slot & ~(uintptr_t)(page_size - 1);
            int restore = page_protection(plan, offset);
            if (mprotect((void *)page, (size_t)page_size, restore | PROT_WRITE) != 0) {
                dl_log(ANDROID_LOG_WARN, "mprotect rw failed at %p: %s", (void *)slot, strerror(DL_ERRNO()));
                break;
            }
            *slot = target->hook;
            if (mprotect((void *)page, (size_t)page_size, restore) != 0) {
                dl_log(ANDROID_LOG_WARN, "mprotect restore failed at %p: %s", (void *)slot, strerror(DL_ERRNO()));
            }
            patched++;
            break;
        }
    }
    return patched;
}

static int patch_object(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size;
    int *total = data;

    if (!info) return 0;
    const char *name = info->dlpi_name;
    if (!name || !*name) return 0;
    if (strstr(name, "/com.google.android.webview")) return 0;
    if (strstr(name, "/com.google.android.gms")) return 0;
    if (strncmp(name, "/data/app/", 10) != 0 &&
        strncmp(name, "/data/user/", 11) != 0 &&
        strncmp(name, "/data/data/", 11) != 0) {
        return 0;
    }

    const char *filter = g_patch_filter;
    if (filter && !strstr(name, filter)) return 0;

    const ElfW(Phdr) *phdr = info->dlpi_phdr;
    if (!phdr || info->dlpi_phnum == 0) return 0;

    const ElfW(Dyn) *dynamic = NULL;
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; i++) {
        if (phdr[i].p_type == PT_DYNAMIC) {
            dynamic = (const ElfW(Dyn) *)(info->dlpi_addr + phdr[i].p_vaddr);
            break;
        }
    }
    if (!dynamic) return 0;

    ElfW(Addr) jmprel = 0, jmprel_size = 0, pltrel = 0;
    ElfW(Addr) rela = 0, rela_size = 0, rel = 0, rel_size = 0;
    ElfW(Addr) symtab = 0, strtab = 0;

    for (const ElfW(Dyn) *d = dynamic; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
        case DT_SYMTAB: symtab = d->d_un.d_ptr; break;
        case DT_STRTAB: strtab = d->d_un.d_ptr; break;
        case DT_JMPREL: jmprel = d->d_un.d_ptr; break;
        case DT_PLTRELSZ: jmprel_size = d->d_un.d_val; break;
        case DT_PLTREL: pltrel = d->d_un.d_val; break;
        case DT_RELA: rela = d->d_un.d_ptr; break;
        case DT_RELASZ: rela_size = d->d_un.d_val; break;
        case DT_REL: rel = d->d_un.d_ptr; break;
        case DT_RELSZ: rel_size = d->d_un.d_val; break;
        default: break;
        }
    }

    if (!symtab || !strtab) return 0;

    reloc_plan plan = {
        info->dlpi_addr,
        phdr,
        info->dlpi_phnum,
        (const ElfW(Sym) *)(info->dlpi_addr + symtab),
        (const char *)(info->dlpi_addr + strtab),
        name,
    };

    if (jmprel && jmprel_size && pltrel == DT_RELA) {
        *total += patch_table(&plan, (const void *)(plan.base + jmprel), jmprel_size, 1);
    }
    if (rela && rela_size) {
        *total += patch_table(&plan, (const void *)(plan.base + rela), rela_size, 1);
    }
    if (rel && rel_size) {
        *total += patch_table(&plan, (const void *)(plan.base + rel), rel_size, 0);
    }
    return 0;
}

static void resolve_targets(void);

static int refresh_modules(const char *filter) {
    /*
     * Libraries that matter here (GLES, EGL, the app's own code) are usually
     * mapped after the shim installs, so their symbols only become resolvable
     * later. Resolving again on every refresh is what lets a late
     * `glGetString` be hooked at all.
     */
    resolve_targets();

    int total = 0;
    dl_lock(&g_patch_lock);
    g_patch_filter = filter;
    dl_iterate_phdr(patch_object, &total);
    g_patch_filter = NULL;
    dl_unlock(&g_patch_lock);
    return total;
}

/* -------------------------------------------------------------- JNI side */

static void resolve_targets(void) {
    dl_lock(&g_lock);
    int all = 1;
    for (size_t i = 0; i < DL_TARGET_COUNT; i++) {
        if (!g_targets[i].resolved) all = 0;
    }
    if (all) {
        dl_unlock(&g_lock);
        return;
    }

    for (size_t i = 0; i < DL_TARGET_COUNT; i++) {
        shim_target *target = &g_targets[i];
        if (target->resolved) continue;

        void *address = dlsym(RTLD_DEFAULT, target->name);
        if (!address) {
            if (!target->optional) dl_log(ANDROID_LOG_WARN, "symbol %s not found", target->name);
            continue;
        }
        *target->real_slot = address;
        target->resolved = 1;
    }
    dl_unlock(&g_lock);
}

static char *jstring_to_utf(JNIEnv *env, jstring value) {
    if (!value) return NULL;
    return (char *)(*env)->GetStringUTFChars(env, value, NULL);
}

static void jstring_release(JNIEnv *env, jstring value, char *chars) {
    if (value && chars) (*env)->ReleaseStringUTFChars(env, value, chars);
}

static shim_config *config_from_jstring(JNIEnv *env, jstring value) {
    char *chars = jstring_to_utf(env, value);
    shim_config *cfg = parse_config(chars);
    jstring_release(env, value, chars);
    return cfg;
}

static int install_config(shim_config *cfg) {
    if (!cfg) return 0;
    __atomic_store_n(&g_config, cfg, __ATOMIC_RELEASE);
    return refresh_modules(NULL);
}

JNIEXPORT jint JNICALL
Java_com_doppelisk_hook_engine_NativeShim_nativeInstall(JNIEnv *env, jclass clazz, jstring config) {
    (void)clazz;

    shim_config *cfg = config_from_jstring(env, config);
    int patches = install_config(cfg);
    if (patches == 0 && !cfg) return 0;

    const shim_config *published = cfg_load();
    dl_log(ANDROID_LOG_INFO, "installed version=%d props=%d hidden=%d patches=%d",
           published ? published->version : 0,
           published ? (int)published->prop_count : 0,
           published ? (int)published->hidden_count : 0,
           patches);
    return patches;
}

JNIEXPORT void JNICALL
Java_com_doppelisk_hook_engine_NativeShim_nativeUpdate(JNIEnv *env, jclass clazz, jstring config) {
    (void)clazz;
    shim_config *cfg = config_from_jstring(env, config);
    if (!cfg) return;
    __atomic_store_n(&g_config, cfg, __ATOMIC_RELEASE);
}

JNIEXPORT jint JNICALL
Java_com_doppelisk_hook_engine_NativeShim_nativeRefresh(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return refresh_modules(NULL);
}

JNIEXPORT jint JNICALL
Java_com_doppelisk_hook_engine_NativeShim_nativeRefreshPath(JNIEnv *env, jclass clazz, jstring path) {
    (void)clazz;
    char *chars = jstring_to_utf(env, path);
    int patches = refresh_modules(chars);
    jstring_release(env, path, chars);
    return patches;
}

JNIEXPORT jstring JNICALL
Java_com_doppelisk_hook_engine_NativeShim_nativeProbe(JNIEnv *env, jclass clazz) {
    (void)clazz;

    char model[DL_PROP_VALUE_MAX] = {0};
    char debuggable[DL_PROP_VALUE_MAX] = {0};
    char build_id[DL_PROP_VALUE_MAX] = {0};
    char build_display[DL_PROP_VALUE_MAX] = {0};
    char baseband[DL_PROP_VALUE_MAX] = {0};
    char mac[128] = {0};
    struct utsname uts;

    my_system_property_get("ro.product.model", model);
    my_system_property_get("ro.debuggable", debuggable);
    my_system_property_get("ro.build.id", build_id);
    my_system_property_get("ro.build.display.id", build_display);
    my_system_property_get("gsm.version.baseband", baseband);

    memset(&uts, 0, sizeof uts);
    my_uname(&uts);

    const char *adb_state = "absent";
    int fd = my_open("/data/adb", O_RDONLY, 0);
    if (fd >= 0) {
        close(fd);
        adb_state = "present";
    }

    FILE *stream = my_fopen("/sys/class/net/wlan0/address", "r");
    if (stream) {
        if (!fgets(mac, sizeof mac, stream)) mac[0] = '\0';
        fclose(stream);
        size_t len = strlen(mac);
        while (len > 0 && (mac[len - 1] == '\n' || mac[len - 1] == '\r')) mac[--len] = '\0';
    }

    char probe[4096];
    snprintf(probe, sizeof probe,
             "model\t%s\n"
             "release\t%s\n"
             "kernel_version\t%s\n"
             "machine\t%s\n"
             "build_id\t%s\n"
             "build_display\t%s\n"
             "baseband\t%s\n"
             "hidden_data_adb\t%s\n"
             "mac\t%s\n"
             "debuggable\t%s\n",
             model, uts.release, uts.version, uts.machine,
             build_id, build_display, baseband, adb_state, mac, debuggable);

    return (*env)->NewStringUTF(env, probe);
}

JNIEXPORT jstring JNICALL
Java_com_doppelisk_daemon_NativeNet_originalDst(JNIEnv *env, jobject thiz, jint fd) {
    (void)thiz;

    struct sockaddr_storage address;
    socklen_t length = sizeof address;
    char host[64];
    char out[80];

    memset(&address, 0, sizeof address);
    memset(host, 0, sizeof host);

    if (getsockopt((int)fd, DL_SOL_IP, DL_SO_ORIGINAL_DST, &address, &length) == 0) {
        const struct sockaddr_in *in = (const struct sockaddr_in *)&address;
        if (inet_ntop(AF_INET, &in->sin_addr, host, sizeof host) == NULL) return NULL;
        unsigned port = ntohs(in->sin_port);
        if (port == 0) return NULL;
        if (strchr(host, ':') == NULL) {
            snprintf(out, sizeof out, "%s:%u", host, port);
        } else {
            snprintf(out, sizeof out, "[%s]:%u", host, port);
        }
        return (*env)->NewStringUTF(env, out);
    }

    length = sizeof address;
    memset(&address, 0, sizeof address);
    if (getsockopt((int)fd, DL_SOL_IPV6, DL_IP6T_SO_ORIGINAL_DST, &address, &length) == 0) {
        const struct sockaddr_in6 *in6 = (const struct sockaddr_in6 *)&address;
        if (in6->sin6_family != AF_INET6) return NULL;
        if (inet_ntop(AF_INET6, &in6->sin6_addr, host, sizeof host) == NULL) return NULL;
        unsigned port = ntohs(in6->sin6_port);
        if (port == 0) return NULL;
        snprintf(out, sizeof out, "[%s]:%u", host, port);
        return (*env)->NewStringUTF(env, out);
    }

    return NULL;
}
