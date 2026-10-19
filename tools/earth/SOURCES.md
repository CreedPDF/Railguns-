# Earth source maps

`earth_day_4096.jpg`, `earth_night_4096.jpg` and `earth_bump_roughness_clouds_4096.jpg`
(R = bump, G = roughness, B = clouds) are the Solar System Scope Earth textures
(https://www.solarsystemscope.com/textures/, based on NASA imagery), licensed
CC BY 4.0, as resized and merged in the three.js examples (MIT).

`tools/textures/build_textures.py` grades them into the in-game maps under
`assets/orbital_railgun/textures/environment/`, and the Blender build wraps them on the
Earth sphere.
