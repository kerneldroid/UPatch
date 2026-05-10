#!/system/bin/sh
#######################################################################################
# UPatch Boot Image Unpatcher
#######################################################################################

ARCH=$(getprop ro.product.cpu.abi)

# Load utility functions
. ./util_functions.sh

echo "****************************"
echo " UPatch Boot Image Unpatcher"
echo "****************************"

BOOTIMAGE="$1"
shift 1

[ -e "$BOOTIMAGE" ] || { >&2 echo "- $BOOTIMAGE does not exist!"; exit 1; }
[ -x ./kptools ] || { >&2 echo "- kptools is missing or not executable!"; exit 1; }

echo "- Target image: $BOOTIMAGE"

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

if ./kptools -i kernel -l | grep -q 'patched=false'; then
  echo "- No need to unpatch"
  exit 0
fi

echo "- Kernel has been patched"
if [ -f new-boot.img ]; then
  echo "- Found backup boot image, using it for recovery"
else
  mv -f kernel kernel.ori || { >&2 echo "- Failed to preserve current kernel image"; exit 1; }

  echo "- Unpatching kernel"
  ./kptools -u --image kernel.ori --out kernel "$@"
  unpatch_rc=$?
  if [ $unpatch_rc -ne 0 ]; then
    >&2 echo "- Unpatch error: $unpatch_rc"
    exit $unpatch_rc
  fi

  echo "- Repacking boot image"
  ./kptools repack "$BOOTIMAGE"
  repack_rc=$?
  if [ $repack_rc -ne 0 ]; then
    >&2 echo "- Repack error: $repack_rc"
    exit $repack_rc
  fi
fi

[ -f new-boot.img ] || { >&2 echo "- Missing new-boot.img after recovery step"; exit 1; }

echo "- Flashing boot image"
flash_image new-boot.img "$BOOTIMAGE"
flash_rc=$?
if [ $flash_rc -ne 0 ]; then
  >&2 echo "- Flash error: $flash_rc"
  exit $flash_rc
fi

echo "- Flash successful"

# Reset any error code
true
