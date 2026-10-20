#!/usr/bin/env bash
#
# rgd-ai-chatbot-rag-pipeline
# Generate gene, QTL, strain and cell line markdown for every searchable RGD species:
# human 1, mouse 2, rat 3, chinchilla 4, bonobo 5, dog 6, squirrel 7, pig 9, green monkey 13,
# naked mole-rat 14, black rat 17. Types a species has no objects of simply write nothing.
# Cell lines are generated for rat only (see generateCellLineMD.sh).
for SPECIES in 3 1 2 4 5 6 7 9 13 14 17; do
    ./run.sh --mode generate --type gene --species $SPECIES
    ./run.sh --mode generate --type qtl --species $SPECIES
    ./run.sh --mode generate --type strain --species $SPECIES
done
./run.sh --mode generate --type cellline --species 3
