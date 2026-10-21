#!/usr/bin/env bash
#
# rgd-ai-chatbot-rag-pipeline
# Generate the markdown for every report type. Nothing is embedded: run the embed*.sh
# scripts (and embedObo.sh for ontology terms, which have no generate step) separately.
#
# Species-scoped types run for every searchable RGD species: human 1, mouse 2, rat 3,
# chinchilla 4, bonobo 5, dog 6, squirrel 7, pig 9, green monkey 13, naked mole-rat 14,
# black rat 17. A type a species has no objects of simply writes nothing.
# Cell lines for human (~124K) and mouse (~30K) are mostly Cellosaurus imports; choose at
# embed time whether to include them (embedCellLineMD.sh --species <key>).
for SPECIES in 3 1 2 4 5 6 7 9 13 14 17; do
    ./run.sh --mode generate --type gene --species $SPECIES
    ./run.sh --mode generate --type qtl --species $SPECIES
    ./run.sh --mode generate --type strain --species $SPECIES
    ./run.sh --mode generate --type cellline --species $SPECIES
done
./run.sh --mode generate --type reference
./run.sh --mode generate --type pathway
./run.sh --mode generate --type expectedrange
./run.sh --mode generate --type website
