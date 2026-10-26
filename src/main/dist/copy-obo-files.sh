#!/usr/bin/env bash
#
# rgd-ai-chatbot-rag-pipeline
# Copies the current (undated) .obo file from each ontology directory of the
# ontology-load-pipeline into this pipeline's obo input directory, which is where
# embedService expects them (oboDir = data/markdown/ontology/obo).
#
# Each ontology directory keeps a long history of dated snapshots
# (e.g. RDO_20250104_v1.304.obo, measurement_method_v2026-05-06.obo) alongside a
# single undated "current" file (e.g. RDO.obo). Only the undated file is copied,
# and exactly one is expected per directory -- if a directory has none, or more
# than one, that is reported rather than guessed at.
#
# Usage:
#   ./copy-obo-files.sh              copy (skips files that are already identical)
#   ./copy-obo-files.sh --dry-run    report what would be copied, write nothing
#   ./copy-obo-files.sh --force      copy every file even if unchanged
#   ./copy-obo-files.sh --help

set -euo pipefail

SRC_ROOT="/home/rgddata/pipelines/ontology-load-pipeline/data/ontology"
DEST_DIR="/home/rgddata/pipelines/rgd-ai-chatbot-rag-pipeline/data/markdown/ontology/obo"

# A name is "dated" if it carries YYYYMMDD or YYYY-MM-DD anywhere.
DATE_RE='[0-9]{8}|[0-9]{4}-[0-9]{2}-[0-9]{2}'

DRY_RUN=0
FORCE=0

usage() {
    # Print the header comment block: comment lines from line 3 up to the first
    # line that is not a comment.
    awk 'NR<3 {next} /^#/ {sub(/^# ?/,""); print; next} {exit}' "$0"
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -n|--dry-run) DRY_RUN=1 ;;
        -f|--force)   FORCE=1 ;;
        -h|--help)    usage ;;
        *) echo "copy-obo-files.sh: unknown option '$1' (try --help)" >&2; exit 2 ;;
    esac
    shift
done

[[ -d "$SRC_ROOT" ]] || { echo "ERROR: source not found: $SRC_ROOT" >&2; exit 1; }

echo "source: $SRC_ROOT"
echo "dest:   $DEST_DIR"
[[ $DRY_RUN -eq 1 ]] && echo "mode:   DRY RUN (nothing will be written)"
echo

if [[ ! -d "$DEST_DIR" ]]; then
    if [[ $DRY_RUN -eq 1 ]]; then
        echo "would create directory $DEST_DIR"
    else
        mkdir -p "$DEST_DIR"
        echo "created directory $DEST_DIR"
    fi
    echo
fi

if [[ $DRY_RUN -eq 0 && ! -w "$DEST_DIR" ]]; then
    echo "ERROR: $DEST_DIR is not writable by $(id -un) -- run as rgddata." >&2
    exit 1
fi

copied=0 skipped=0 problems=0

for src_dir in "$SRC_ROOT"/*/; do
    ontology="$(basename "$src_dir")"

    # Undated .obo files in this directory. Symlinks are accepted as well as
    # regular files, since an undated "current" file is often a link to the
    # newest dated snapshot; cp -L below copies the contents either way.
    mapfile -t found < <(
        find "$src_dir" -maxdepth 1 \( -type f -o -type l \) -name '*.obo' -printf '%f\n' 2>/dev/null \
            | grep -Ev "$DATE_RE" \
            | sort || true
    )

    case ${#found[@]} in
        0)
            # Directories with no .obo at all (e.g. mappings/) are not ontologies; stay quiet
            # unless the directory does hold .obo files and they are all dated.
            if find "$src_dir" -maxdepth 1 -name '*.obo' -print -quit 2>/dev/null | grep -q .; then
                echo "WARNING  $ontology: only dated .obo files, no current one -- skipped"
                problems=$((problems + 1))
            fi
            continue
            ;;
        1) ;;
        *)
            echo "WARNING  $ontology: ${#found[@]} undated .obo files, expected 1 -- skipped"
            printf '           %s\n' "${found[@]}"
            problems=$((problems + 1))
            continue
            ;;
    esac

    name="${found[0]}"
    src="$src_dir$name"
    dest="$DEST_DIR/$name"
    size="$(stat -Lc %s "$src")"

    # Skip when the destination already holds identical content.
    if [[ $FORCE -eq 0 && -f "$dest" ]] && cmp -s "$src" "$dest"; then
        printf 'unchanged  %-24s %s (%s bytes)\n' "$ontology" "$name" "$size"
        skipped=$((skipped + 1))
        continue
    fi

    if [[ $DRY_RUN -eq 1 ]]; then
        printf 'would copy %-24s %s (%s bytes)\n' "$ontology" "$name" "$size"
        copied=$((copied + 1))
        continue
    fi

    # Copy to a temporary name in the destination, then move into place, so a
    # reader never sees a half-written .obo.
    tmp="$DEST_DIR/.$name.part$$"
    cp -Lp "$src" "$tmp"
    mv -f "$tmp" "$dest"

    # Confirm the copy landed intact.
    if ! cmp -s "$src" "$dest"; then
        echo "ERROR    $ontology: $name differs from source after copy" >&2
        problems=$((problems + 1))
        continue
    fi

    printf 'copied     %-24s %s (%s bytes)\n' "$ontology" "$name" "$size"
    copied=$((copied + 1))
done

echo
if [[ $DRY_RUN -eq 1 ]]; then
    echo "Dry run: $copied to copy, $skipped already current, $problems problem(s)."
else
    echo "Done: $copied copied, $skipped unchanged, $problems problem(s)."
fi

[[ $problems -gt 0 ]] && exit 1
exit 0
