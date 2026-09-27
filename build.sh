#!/bin/sh
# Compiles src/ into build/classes and packages build/csv2sql.jar
set -e
cd "$(dirname "$0")"

rm -rf build/classes
mkdir -p build/classes

find src -name '*.java' > build/sources.txt
javac -Xlint:all -Werror -encoding UTF-8 -d build/classes @build/sources.txt

jar cfe build/csv2sql.jar csv2sql.CsvToSql -C build/classes .

echo "built build/csv2sql.jar"
