#!/bin/sh
# By SakuraKyuo

OUTFD=/proc/self/fd/$2
WORKDIR=/dev/tmp/install
LOG_FILE=$WORKDIR/log
KPTOOLS=./lib/arm64-v8a/libkptools.so

ui_print() {
  printf 'ui_print %s\nui_print\n' "$1" >> "$OUTFD"
}

ui_printfile() {
  while IFS='' read -r line || [ -n "$line" ]; do
    ui_print "$line"
  done < "$1"
}

kernelFlagsErr() {
  ui_print "- Installation has aborted!"
  ui_print "- UPatch requires CONFIG_KALLSYMS to be enabled."
  ui_print "- Your kernel reports it as missing."
  exit 1
}

upatchNote() {
  ui_print "- UPatch patch done"
  ui_print "- The original boot image was saved to /data/boot.img"
  ui_print "- If you hit a boot loop, reboot to recovery and flash it"
  exit 0
}

failed() {
  [ -f "$LOG_FILE" ] && ui_printfile "$LOG_FILE"
  ui_print "- UPatch patch failed."
  ui_print "- Please report the failure with the generated log output."
  exit 1
}

patch_boot_image() {
  target_block="$1"

  "$KPTOOLS" unpack boot.img || failed
  "$KPTOOLS" -i ./kernel -f | grep -q 'CONFIG_KALLSYMS=y' || kernelFlagsErr

  mv -f kernel kernel-origin || failed
  "$KPTOOLS" -p --image kernel-origin --kpimg ./assets/kpimg --out ./kernel > "$LOG_FILE" 2>&1 || failed
  ui_printfile "$LOG_FILE"

  "$KPTOOLS" repack boot.img || failed
  [ -f "$WORKDIR/new-boot.img" ] || failed
  dd if="$WORKDIR/new-boot.img" of="$target_block" || failed

  mv -f boot.img /data/boot.img >/dev/null 2>&1 || true
  upatchNote
}

main() {
  cd "$WORKDIR" || exit 1

  chmod a+x ./assets/kpimg "$KPTOOLS"

  slot=$(getprop ro.boot.slot_suffix)

  if [ -n "$slot" ]; then
    ui_print ""
    ui_print "- You are using an A/B device."
    ui_print "- Install script by SakuraKyuo"
    ui_print ""

    dd if="/dev/block/by-name/boot$slot" of="$WORKDIR/boot.img" || failed
    ui_print "- Detected boot partition."
    patch_boot_image "/dev/block/by-name/boot$slot"
  else
    ui_print "- You are using an A-only device."
    ui_print ""

    dd if=/dev/block/by-name/boot of="$WORKDIR/boot.img" || failed
    ui_print "- Detected boot partition."
    patch_boot_image /dev/block/by-name/boot
  fi
}

main
