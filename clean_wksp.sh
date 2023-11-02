#!/bin/bash

# Remove all .sv and .anno.json files from the current directory
find . -type f \( -name "*.sv" -o -name "*.anno.json" \) -delete

# Remove all contents of the ./test_run_dir directory
rm -rf ./test_run_dir/*
