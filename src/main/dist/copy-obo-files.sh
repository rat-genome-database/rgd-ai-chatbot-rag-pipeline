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
# The ontology load pipeline keeps .obo files only for the ontologies RGD maintains
# (RDO, PW, CMO, MMO, XCO, RS). The others it loads (GO, MP, HP, VT, CHEBI, ...) are
# downloaded here from the same sources it uses (its properties/AppConfigure.xml),
# saved as <ID>.obo. A failed download is reported and the previous file is kept.
# Not downloaded: ORDO and MI (need a BioPortal API key), NCBITaxon (no public .obo
# source in that config) and CVCL (Cellosaurus cell lines, not an ontology).
#
# Usage:
#   ./copy-obo-files.sh              copy and download (skips files that are already identical)
#   ./copy-obo-files.sh --dry-run    report what would be copied, write nothing
#   ./copy-obo-files.sh --force      copy every file even if unchanged
#   ./copy-obo-files.sh --no-download  copy the local files only
#   ./copy-obo-files.sh --help

set -euo pipefail

SRC_ROOT="/home/rgddata/pipelines/ontology-load-pipeline/data/ontology"
DEST_DIR="/home/rgddata/pipelines/rgd-ai-chatbot-rag-pipeline/data/markdown/ontology/obo"

# A name is "dated" if it carries YYYYMMDD or YYYY-MM-DD anywhere.
DATE_RE='[0-9]{8}|[0-9]{4}-[0-9]{2}-[0-9]{2}'

# Ontologies downloaded from their publishers: <ID> <url>
DOWNLOADS='
GO      http://purl.obolibrary.org/obo/go/snapshot/go-basic.obo
MP      http://www.informatics.jax.org/downloads/reports/MPheno_OBO.ontology
HP      https://raw.githubusercontent.com/obophenotype/human-phenotype-ontology/master/hp.obo
VT      https://raw.githubusercontent.com/AnimalGenome/vertebrate-trait-ontology/master/current_release/vt.obo
CHEBI   ftp://ftp.ebi.ac.uk/pub/databases/chebi/ontology/chebi.obo
UBERON  http://purl.obolibrary.org/obo/uberon/basic.obo
CL      http://purl.obolibrary.org/obo/cl/cl-basic.obo
SO      https://github.com/The-Sequence-Ontology/SO-Ontologies/raw/master/Ontology_Files/so-simple.obo
MA      http://www.informatics.jax.org/downloads/reports/adult_mouse_anatomy.obo
NBO     http://purl.obolibrary.org/obo/nbo.obo
ZFA     http://purl.obolibrary.org/obo/zfa.obo
MONDO   http://purl.obolibrary.org/obo/mondo/mondo.obo
EFO     https://www.ebi.ac.uk/efo/efo.obo
OBA     http://purl.obolibrary.org/obo/oba.obo
CS      https://download.rgd.mcw.edu/ontology/chinchilla/CS.obo
'

DRY_RUN=0
FORCE=0
DOWNLOAD=1

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
        --no-download) DOWNLOAD=0 ;;
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

if [[ $DOWNLOAD -eq 1 ]]; then
    echo
    while read -r id url; do
        [[ -z "$id" ]] && continue
        dest="$DEST_DIR/$id.obo"

        if [[ $DRY_RUN -eq 1 ]]; then
            printf 'would get  %-24s %s\n' "$id" "$url"
            copied=$((copied + 1))
            continue
        fi

        tmp="$DEST_DIR/.$id.obo.part$$"
        if ! curl -fsSL --retry 3 --max-time 1800 -o "$tmp" "$url"; then
            rm -f "$tmp"
            echo "WARNING  $id: download failed from $url -- keeping the previous file" >&2
            problems=$((problems + 1))
            continue
        fi
        # A server can answer 200 with an HTML error page; an ontology has [Term] stanzas.
        if ! grep -q -m1 '^\[Term\]' "$tmp"; then
            rm -f "$tmp"
            echo "WARNING  $id: $url returned no OBO terms -- keeping the previous file" >&2
            problems=$((problems + 1))
            continue
        fi
        size="$(stat -Lc %s "$tmp")"
        if [[ $FORCE -eq 0 && -f "$dest" ]] && cmp -s "$tmp" "$dest"; then
            rm -f "$tmp"
            printf 'unchanged  %-24s %s.obo (%s bytes)\n' "$id" "$id" "$size"
            skipped=$((skipped + 1))
            continue
        fi
        mv -f "$tmp" "$dest"
        printf 'downloaded %-24s %s.obo (%s bytes)\n' "$id" "$id" "$size"
        copied=$((copied + 1))
    done <<< "$DOWNLOADS"
fi

echo
if [[ $DRY_RUN -eq 1 ]]; then
    echo "Dry run: $copied to copy, $skipped already current, $problems problem(s)."
else
    echo "Done: $copied copied, $skipped unchanged, $problems problem(s)."
fi

[[ $problems -gt 0 ]] && exit 1
exit 0
