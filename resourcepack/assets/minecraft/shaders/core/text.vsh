#version 330
#extension GL_ARB_separate_shader_objects : require
// IdCards: this is the vanilla 26.3 core/text shader with two marked additions (IDCARDS BEGIN/END): an include and one
// call. All logic is in assets/idcards/shaders/include/idcards_text_*.glsl; text that is not the ID card HUD takes
// exactly the vanilla path. When SuperbVehicles, CCTV or Radio are installed the plugin ships their merged shader
// with the same two additions instead of this file (see README, "Sharing the text shader").

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
// ===== IDCARDS BEGIN =====
#include <idcards:idcards_text_vertex.glsl>
// ===== IDCARDS END =====

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 3) in ivec2 UV2;
#endif

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
uniform sampler2D Sampler2;
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#endif

layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
#else
    vertexColor = Color;
#endif
    texCoord0 = UV0;
    // ===== IDCARDS BEGIN =====
    idcards_text_vertex(Color);
    // ===== IDCARDS END =====
}
