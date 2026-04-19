#!/usr/bin/env python3
"""
Patch an ELF .so: flip LOCAL bindings to GLOBAL in the .dynsym for the bss/end markers
that new LLD rejects when linking against pre-built shared libraries
(found local symbol 'X' in global part of symbol table).

Usage: patch_so.py <input.so> <output.so>
"""
import sys, struct
from pathlib import Path

TARGETS = {
    "__bss_start", "__bss_end", "__bss_start__", "__bss_end__",
    "_bss_end__", "_edata", "_end", "__end__",
}

STB_GLOBAL = 1  # ELF symbol binding

def main():
    src, dst = sys.argv[1], sys.argv[2]
    data = bytearray(Path(src).read_bytes())

    # parse ELF headers via pyelftools to find the .dynsym section offset/entsize
    from elftools.elf.elffile import ELFFile
    with open(src, "rb") as f:
        ef = ELFFile(f)
        dynsym = ef.get_section_by_name(".dynsym")
        if dynsym is None:
            print("no .dynsym"); sys.exit(1)
        sh_offset = dynsym['sh_offset']
        entsize = dynsym['sh_entsize']
        count = dynsym.num_symbols()
        print(f".dynsym offset=0x{sh_offset:x} entsize={entsize} count={count}")

        # Layout of Elf64_Sym (LE, aarch64):
        #   st_name:    u32   (offset 0)
        #   st_info:    u8    (offset 4)   bind<<4 | type
        #   st_other:   u8    (offset 5)
        #   st_shndx:   u16   (offset 6)
        #   st_value:   u64   (offset 8)
        #   st_size:    u64   (offset 16)
        patched = 0
        for i, sym in enumerate(dynsym.iter_symbols()):
            if sym.name in TARGETS:
                bind = sym['st_info']['bind']
                info_off = sh_offset + i * entsize + 4
                old = data[info_off]
                new = (STB_GLOBAL << 4) | (old & 0x0F)
                data[info_off] = new
                print(f"  patch {sym.name}: bind {bind} -> GLOBAL (byte 0x{old:02x} -> 0x{new:02x})")
                patched += 1
        print(f"patched {patched} symbols")

    Path(dst).write_bytes(bytes(data))

if __name__ == "__main__":
    main()
