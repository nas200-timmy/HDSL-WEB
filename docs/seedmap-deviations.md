# Seed-map deviations

Known differences between the Java seed-map core (and the cubiomes C
reference it is ported from) and the real Minecraft server. These are not
porting bugs — L1 confirms the Java core matches cubiomes exactly — but
upstream differences between cubiomes and specific game builds.

## 1.21.2

- **birch_forest → forest**: one sample in 5184 at seed `-6570214734548007003`,
  block `(60, 137, -16)`. The real server stores `minecraft:birch_forest`;
  cubiomes and the Java core return `forest` (id 4). The deviation is in the
  upstream biome tree, not in the Java port.

## 1.21.6

- **pale_garden → dark_forest**: the 1.21.6 server generates `pale_garden`
  where cubiomes' 1.21-Winter-Drop tree returns `dark_forest`. Example: seed
  `6333384005875658773`, blocks around `(0, 188, 20)`. This suggests the
  Winter Drop parameter list changed again after the cubiomes table was
  generated.
- **birch_forest → forest**: same upstream deviation as 1.21.2.

## Policy

Versions with registered deviations are considered **experimental** for the
seed-map core. The panel keeps the real-server backend as the default for
detail maps; the biome overview uses the core because the deviations are
small, localised and acceptable for a preview. Any version whose deviation
rate becomes significant must be reverted to the server backend until the
upstream tables catch up.
