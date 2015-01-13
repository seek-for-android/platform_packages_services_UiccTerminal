LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_PACKAGE_NAME := UiccTerminal
LOCAL_CERTIFICATE := platform

LOCAL_JAVA_LIBRARIES := framework org.simalliance.openmobileapi

LOCAL_DEX_PREOPT := false

include $(BUILD_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))
