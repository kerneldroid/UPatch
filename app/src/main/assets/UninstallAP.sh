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

apatchNote() {
  ui_print "- UPatch unpatch done"
  exit 0
}

failed() {
  [ -f "$LOG_FILE" ] && ui_printfile "$LOG_FILE"
  ui_print "- UPatch unpatch failed."
  ui_print "- Please report the failure with the generated log output."
  exit 1
}

unpatch_boot_image() {
  target_block="$1"

  "$KPTOOLS" unpack boot.img || failed
  if "$KPTOOLS" -i ./kernel -l | grep -q 'patched=false'; then
    ui_print "- Boot image is already clean."
    exit 0
  fi

  mv -f kernel kernel-origin || failed
  "$KPTOOLS" -u --image kernel-origin --out ./kernel > "$LOG_FILE" 2>&1 || failed
  [ -s "$LOG_FILE" ] && ui_printfile "$LOG_FILE"

  "$KPTOOLS" repack boot.img || failed
  [ -f "$WORKDIR/new-boot.img" ] || failed
  dd if="$WORKDIR/new-boot.img" of="$target_block" || failed
  apatchNote
}

main() {
  cd "$WORKDIR" || exit 1

  chmod a+x "$KPTOOLS"

  slot=$(getprop ro.boot.slot_suffix)

  if [ -n "$slot" ]; then
    ui_print ""
    ui_print "- You are using an A/B device."
    ui_print ""

    dd if="/dev/block/by-name/boot$slot" of="$WORKDIR/boot.img" || failed
    ui_print "- Detected boot partition."
    unpatch_boot_image "/dev/block/by-name/boot$slot"
  else
    ui_print "- You are using an A-only device."
    ui_print ""

    dd if=/dev/block/by-name/boot of="$WORKDIR/boot.img" || failed
    ui_print "- Detected boot partition."
    unpatch_boot_image /dev/block/by-name/boot
  fi
}

main
