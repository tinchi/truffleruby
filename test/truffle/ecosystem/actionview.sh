#!/usr/bin/env bash

set -e
set -x

bin/truffleruby lib/truffleruby-tool/bin/truffleruby-tool \
    --dir truffleruby-gem-test-pack/gem-testing ci --offline actionview
