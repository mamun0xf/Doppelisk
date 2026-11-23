# Builds libdoppelisk_shim.so with a plain cross compiler instead of the NDK.
#
# The shim is loaded into an Android app process by LSPosed, so it has to link
# against bionic, not glibc. There is no linux-aarch64 NDK toolchain on this
# machine, so the link uses the device's own libc/liblog/libdl, copied out as
# stubs. README.md next to this file has the full story.
#
# -mno-outline-atomics stops the compiler from calling __aarch64_swp4_acq and
# friends; bionic does not export them and the library would fail to load.
# -nostdlib keeps glibc out of the link so every symbol resolves against bionic
# at runtime. -fno-stack-protector avoids the glibc TLS model for the canary.

# GNU make predefines CC as `cc`, and `?=` treats that default as a user
# setting, so it would silently build for the host. Replace only the default;
# an explicit CC on the command line or in the environment still wins.
ifeq ($(origin CC),default)
CC := aarch64-linux-gnu-gcc
endif

BIONIC_DIR ?= /opt/android-bionic/lib
JAVA_HOME ?= /usr/lib/jvm/java-21-openjdk
READELF ?= readelf
NM ?= nm

SRC := doppelisk_shim.c
OBJ := $(SRC:.c=.o)
LIB := libdoppelisk_shim.so
JNILIBS := ../jniLibs/arm64-v8a

CFLAGS := -O2 -Wall -Wextra -fPIC -fno-stack-protector \
          -fno-asynchronous-unwind-tables -mno-outline-atomics \
          -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/linux
LDFLAGS := -shared -nostdlib -Wl,--no-as-needed -Wl,-soname,$(LIB)
LDLIBS := -L$(BIONIC_DIR) -llog -lc -ldl

.DELETE_ON_ERROR:
.PHONY: all check clean inspect

all: $(JNILIBS)/$(LIB)

# Fail here rather than at the link, where a missing stub would only show up as
# "cannot find -lc".
check:
	@test -f $(JAVA_HOME)/include/jni.h || { \
		echo "error: no JNI headers in $(JAVA_HOME)/include"; \
		echo "set JAVA_HOME to a JDK 17 or newer."; \
		exit 1; }
	@test -f $(BIONIC_DIR)/libc.so || { \
		echo "error: $(BIONIC_DIR)/libc.so is missing."; \
		echo "copy libc.so, liblog.so and libdl.so from the device's /system/lib64"; \
		echo "into $(BIONIC_DIR) (inside the chroot that is /proc/1/root/system/lib64)."; \
		exit 1; }

$(OBJ): $(SRC)
	$(CC) $(CFLAGS) -c $< -o $@

# Order-only: check runs on every build but never forces a relink.
$(LIB): $(OBJ) | check
	$(CC) $(LDFLAGS) -o $@ $< $(LDLIBS)

$(JNILIBS)/$(LIB): $(LIB)
	@mkdir -p $(dir $@)
	install -m 644 $< $@

# The copy under jniLibs is a tracked artifact; clean only removes local
# intermediates.
clean:
	rm -f $(OBJ) $(LIB)

# A build is only good if it needs bionic and still exports the JNI entry
# points, so this is enough to sanity-check one without a phone.
inspect: $(LIB)
	@echo "NEEDED:"
	@$(READELF) -d $(LIB) | grep NEEDED
	@echo
	@echo "JNI exports:"
	@$(NM) -D --defined-only $(LIB) | grep ' Java_'
