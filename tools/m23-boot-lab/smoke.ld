ENTRY(_start)
PHDRS { code PT_LOAD FLAGS(5); }
SECTIONS {
  . = 0x40080000;
  .text : { *(.text*) *(.rodata*) } :code
  /DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) *(.gnu*) }
}
