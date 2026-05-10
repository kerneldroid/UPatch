#!/system/bin/sh
#######################################################################################
# UPatch Boot Image Patcher
#######################################################################################
#
# Usage: boot_patch.sh <superkey> <bootimage> [flash_to_device] [ARGS_PASS_TO_KPTOOLS]
#
# This script should be placed in a directory with the following files:
#
# File name          Type          Description
#
# boot_patch.sh      script        A script to patch boot image for UPatch.
#                  (this file)      The script will use files in its same
#                                  directory to complete the patching process.
# bootimg            binary        The target boot image
# kpimg              binary        KernelPatch core Image
# kptools            executable    The KernelPatch tools binary to inject kpimg to kernel Image
#
#######################################################################################

ARCH=$(getprop ro.product.cpu.abi)

# Load utility functions
. ./util_functions.sh

echo "****************************"
echo " UPatch Boot Image Patcher"
echo "****************************"

SUPERKEY="$1"
BOOTIMAGE="$2"
FLASH_TO_DEVICE="false"

if [ "$3" = "true" ] || [ "$3" = "false" ]; then
  FLASH_TO_DEVICE="$3"
  shift 3
else
  shift 2
fi

[ -n "$SUPERKEY" ] || { >&2 echo "- SuperKey is empty!"; exit 1; }
[ -e "$BOOTIMAGE" ] || { >&2 echo "- $BOOTIMAGE does not exist!"; exit 1; }
[ -x ./kptools ] || { >&2 echo "- kptools is missing or not executable!"; exit 1; }

if [ ! -f kernel ]; then
  echo "- Unpacking boot image"
  set -x
  ./kptools unpack "$BOOTIMAGE" "$@"
  unpack_rc=$?
  set +x
  if [ $unpack_rc -ne 0 ]; then
    >&2 echo "- Unpack error: $unpack_rc"
    exit $unpack_rc
  fi
fi

if ! ./kptools -i kernel -f | grep -q 'CONFIG_KALLSYMS=y'; then
  >&2 echo "- Patcher has aborted!"
  >&2 echo "- UPatch requires CONFIG_KALLSYMS to be enabled."
  >&2 echo "- Your kernel reports it as missing."
  exit 1
fi

if ./kptools -i kernel -l | grep -q 'patched=false'; then
  echo "- Backing up original boot image"
  cp -f "$BOOTIMAGE" "ori.img" >/dev/null 2>&1 || echo "- Warning: failed to refresh ori.img backup"
fi

mv -f kernel kernel.ori || { >&2 echo "- Failed to preserve original kernel image"; exit 1; }

echo "- Patching kernel"
set -x
./kptools -p -i kernel.ori -S "$SUPERKEY" -k kpimg -o kernel "$@"
patch_rc=$?
set +x

if [ $patch_rc -ne 0 ]; then
  >&2 echo "- Patch kernel error: $patch_rc"
  exit $patch_rc
fi

echo "- Repacking boot image"
./kptools repack "$BOOTIMAGE"
repack_rc=$?
if [ $repack_rc -ne 0 ]; then
  >&2 echo "- Repack error: $repack_rc"
  exit $repack_rc
fi

if ! ./kptools -i kernel.ori -f | grep -q 'CONFIG_KALLSYMS_ALL=y'; then
  echo "- Warning: CONFIG_KALLSYMS_ALL is not set."
  echo "- UPatch completed, but the device may fail to boot."
  echo "- Keep the original boot image backup nearby."
fi

if [ "$FLASH_TO_DEVICE" = "true" ]; then
  [ -f new-boot.img ] || { >&2 echo "- new-boot.img was not generated"; exit 1; }
  if [ ! -b "$BOOTIMAGE" ] && [ ! -c "$BOOTIMAGE" ]; then
    >&2 echo "- $BOOTIMAGE is not a block or character device"
    exit 1
  fi

  echo "- Flashing new boot image"
  flash_image new-boot.img "$BOOTIMAGE"
  flash_rc=$?
  if [ $flash_rc -ne 0 ]; then
    >&2 echo "- Flash error: $flash_rc"
    exit $flash_rc
  fi

  echo "- Successfully flashed!"
else
  echo "- Successfully patched!"
fi
