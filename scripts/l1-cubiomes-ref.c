/*
 * L1 reference query tool. Build against cubiomes, then feed it version, seed
 * and coordinates on stdin; it prints the biome id at each point.
 *
 * This file is a test utility only and is never shipped in the panel jar.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "generator.h"
#include "biomes.h"

static int versionFromString(const char *s)
{
    if (strcmp(s, "1.18.2") == 0 || strcmp(s, "1.18") == 0) return MC_1_18_2;
    if (strcmp(s, "1.20.6") == 0 || strcmp(s, "1.20") == 0) return MC_1_20_6;
    if (strcmp(s, "1.21.1") == 0) return MC_1_21_1;
    if (strcmp(s, "1.21.3") == 0 || strcmp(s, "1.21.4") == 0 || strcmp(s, "1.21") == 0) return MC_1_21_WD;
    return MC_UNDEF;
}

int main(int argc, char **argv)
{
    if (argc < 3)
    {
        fprintf(stderr, "usage: %s <version> <seed> [x y z ...]\n", argv[0]);
        return 1;
    }
    int mc = versionFromString(argv[1]);
    if (mc == MC_UNDEF)
    {
        fprintf(stderr, "unsupported version %s\n", argv[1]);
        return 1;
    }
    uint64_t seed = strtoull(argv[2], NULL, 10);

    Generator g;
    setupGenerator(&g, mc, 0);
    applySeed(&g, DIM_OVERWORLD, seed);

    if (argc >= 6)
    {
        int x = atoi(argv[3]);
        int y = atoi(argv[4]);
        int z = atoi(argv[5]);
        if (argc >= 7 && strcmp(argv[6], "climate") == 0)
        {
            int64_t np[6];
            sampleBiomeNoise(&g.bn, np, x, y, z, NULL, SAMPLE_NO_BIOME);
            printf("%lld %lld %lld %lld %lld %lld\n",
                (long long)np[0], (long long)np[1], (long long)np[2],
                (long long)np[3], (long long)np[4], (long long)np[5]);
            return 0;
        }
        printf("%d\n", getBiomeAt(&g, 4, x, y, z));
        return 0;
    }

    char line[256];
    while (fgets(line, sizeof(line), stdin))
    {
        int x, y, z;
        if (sscanf(line, "%d %d %d", &x, &y, &z) != 3)
            continue;
        printf("%d\n", getBiomeAt(&g, 4, x, y, z));
        fflush(stdout);
    }
    return 0;
}
