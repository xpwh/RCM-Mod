#version 330

// Ballistic Missiles' own uniform block, set on every render pass (Wind.java): the wind and blast waves for the
// terrain, and the thunderstorm coming in from the horizon for the clouds and the sky (StormFront.java).
layout(std140) uniform BmWind {
    vec4 WindInfo;      // xy: direction (x, z), z: strength, w: seconds
    vec4 BlastPos[4];   // xyz: relative to the camera block, w: seconds since it went off (< 0: none)
    vec4 BlastShape[4]; // x: speed of the front (blocks/s), y: reach, z: how hard it pushes
    vec4 StormCell;     // xy: centre (x, z) relative to the camera block, z: radius, w: strength (0: none)
    vec4 StormInfo;     // x: sheet lightning in it (0-1), yz: where in it (x, z from the centre), w: the cloud base over the camera
    vec4 StormBolt;     // x: a bolt to the ground (0-1), y: its seed, z: where across the cell (blocks), w: unused
    vec4 WashPos[4];    // downwash (rotors, exhaust): xyz where it hits the ground, relative to the camera block; w: strength (0: none)
    vec4 WashShape[4];  // x: radius (blocks)
};
