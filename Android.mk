LOCAL_PATH:= $(call my-dir)

# Build the Telecom service.
include $(CLEAR_VARS)

LOCAL_JAVA_LIBRARIES := telephony-common

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_PACKAGE_NAME := Telecom

LOCAL_CERTIFICATE := platform
LOCAL_PRIVILEGED_MODULE := true

LOCAL_PROGUARD_FLAGS := $(proguard.flags)

# Workaround for "local variable type mismatch" error.
LOCAL_DX_FLAGS += --no-locals

TELECOMM_CALLINFO_PROVIDER ?= $(LOCAL_PATH)/callinfo_provider

include $(TELECOMM_CALLINFO_PROVIDER)/provider.mk

include $(BUILD_PACKAGE)

# Build the test package.
include $(call all-makefiles-under,$(LOCAL_PATH))
