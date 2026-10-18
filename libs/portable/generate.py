#!/usr/bin/env python3

import os
import optparse
from hashlib import md5
from pathlib import Path
import subprocess
import brotli
import datetime

# 4GB maximum
length_count = 4
# encoding
encoding = 'utf-8'

# output: {path: (compressed_data, file_md5)}


def fail_walk(error):
    raise error


def generate_md5_table(folder: str, level) -> dict:
    res: dict = dict()
    source = Path(folder).resolve()
    for root, directories, files in os.walk(source, onerror=fail_walk):
        directories.sort()
        # Do not follow a linked file or directory outside the compiled bundle.
        for name in directories + files:
            Path(root, name).resolve().relative_to(source)
        for name in sorted(files):
            md5_generator = md5()
            full_path = Path(root, name)
            relative_path = os.path.join('.', str(full_path.relative_to(source)))
            print(f"Processing {relative_path}...")
            with full_path.open("rb") as f:
                content = f.read()
            content_compressed = brotli.compress(
                content, quality=level)
            md5_generator.update(content)
            md5_code = md5_generator.hexdigest().encode(encoding=encoding)
            res[relative_path] = (content_compressed, md5_code)
    return res


def write_package_metadata(md5_table: dict, output_folder: str, exe: str):
    output_path = os.path.join(output_folder, "data.bin")
    with open(output_path, "wb") as f:
        f.write("ashell".encode(encoding=encoding))
        for path in md5_table.keys():
            (compressed_data, md5_code) = md5_table[path]
            data_length = len(compressed_data)
            path = path.encode(encoding=encoding)
            # path length & path
            f.write((len(path)).to_bytes(length=length_count, byteorder='big'))
            f.write(path)
            # data length & compressed data
            f.write(data_length.to_bytes(
                length=length_count, byteorder='big'))
            f.write(compressed_data)
            # md5 code
            f.write(md5_code)
        # end
        f.write("ashell".encode(encoding=encoding))
        # executable
        f.write(exe.encode(encoding='utf-8'))
    print(f"Metadata has been written to {output_path}")

def write_app_metadata(output_folder: str):
    output_path = os.path.join(output_folder, "app_metadata.toml")
    with open(output_path, "w") as f:
        f.write(f"timestamp = {int(datetime.datetime.now().timestamp() * 1000)}\n")
    print(f"App metadata has been written to {output_path}")

def build_portable(output_folder: str, target: str):
    command = ["cargo", "build", "--release"]
    if target:
        command.extend(["--target", target])
    # A failed Cargo invocation must fail Python and its CMD/CI caller as well.
    subprocess.run(command, cwd=output_folder, check=True)

# Linux: python3 generate.py -f ../rustdesk-portable-packer/test -o . -e ./test/main.py
# Windows: python3 .\generate.py -f ..\ashell\flutter\build\windows\runner\Debug\ -o . -e ..\ashell\flutter\build\windows\runner\Debug\ashell.exe


if __name__ == '__main__':
    parser = optparse.OptionParser()
    parser.add_option("-f", "--folder", dest="folder",
                      help="folder to compress")
    parser.add_option("-o", "--output", dest="output_folder",
                      help="the root of portable packer project, default is './'")
    parser.add_option("-e", "--executable", dest="executable",
                      help="specify startup file in --folder, default is ashell.exe")
    parser.add_option("-t", "--target", dest="target",
                      help="the target used by cargo")
    parser.add_option("-l", "--level", dest="level", type="int",
                      help="compression level, default is 11, highest", default=11)
    (options, args) = parser.parse_args()
    source = Path(options.folder or './ashell').resolve()
    output = Path(options.output_folder or './').resolve()
    if not source.is_dir():
        parser.error("The source folder does not exist")
    if not output.is_dir():
        parser.error("The portable project folder does not exist")
    if output == source or source in output.parents:
        parser.error("The portable project must be outside the source bundle")

    requested_exe = Path(options.executable or 'ashell.exe')
    executable = requested_exe.resolve()
    if source not in executable.parents:
        executable = (source / requested_exe).resolve()
    try:
        relative_exe = executable.relative_to(source)
    except ValueError:
        parser.error("The executable must locate in source folder")
    if not executable.is_file():
        parser.error("The startup executable does not exist")
    exe = os.path.join('.', str(relative_exe))
    folder = str(source)
    output_folder = str(output)
    print("Executable path: " + exe)
    print("Compression level: " + str(options.level))
    md5_table = generate_md5_table(folder, options.level)
    write_package_metadata(md5_table, output_folder, exe)
    write_app_metadata(output_folder)
    build_portable(output_folder, options.target)
