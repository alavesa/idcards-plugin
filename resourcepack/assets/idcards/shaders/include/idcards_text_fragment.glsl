#ifndef IDCARDS_TEXT_FRAGMENT_GLSL
#define IDCARDS_TEXT_FRAGMENT_GLSL

// =====================================================================================================================
// IdCards: fragment part of the core/text override. It colours the ID card HUD.
//
// The vertex part (idcards_text_vertex.glsl) hands over idcData: -1 for all ordinary text, else the marker's data
// bits. For -1, and for all world text (this code only exists in the IS_GUI variants), the colour computed by
// vanilla comes back unchanged. For card glyphs the marker colour itself must not be seen, so:
//   art slot    the card texture as drawn, plus (IDC_EFFECTS) faint scanlines and a slow diagonal sheen
//   photo slot  the skin texels as they are (face and hat quads), with the same scanlines
//   text slots  the glyph shape in the palette colour of data bits 5-9 (always full bright: GUI text has no lighting)
// =====================================================================================================================

#include <minecraft:globals.glsl>

#ifdef IS_GUI
#include <idcards:idcards_layout.glsl>
#include <idcards:idcards_config.glsl>

layout(location = 7) flat in int idcData;
layout(location = 8) in vec2 idcCorner;
#endif

vec4 idcards_text_fragment(vec4 color, vec4 texColor, vec4 vertColor, vec4 colorModulator) {
#ifdef IS_GUI
    if (idcData < 0) return color;
    int slot = idcData & 31;
    float alpha = texColor.a * vertColor.a;
    float t = GameTime * 1200.0;   // seconds
    if (slot == IDC_SLOT_ART) {
        vec2 p = idcCorner * IDC_CARD;                       // card pixel
        vec3 rgb = texColor.rgb;
        if (IDC_EFFECTS > 0.5) {
            if (mod(floor(p.y), 2.0) < 1.0) rgb *= 0.965;
            float band = mod(t * 70.0, IDC_CARD.x * 3.0) - IDC_CARD.x * 0.5;
            rgb += vec3(0.085, 0.10, 0.12) * smoothstep(22.0, 0.0, abs(p.x + p.y * 0.55 - band));
        }
        return vec4(rgb, alpha) * colorModulator;
    }
    if (slot == IDC_SLOT_PHOTO) {
        vec3 rgb = texColor.rgb;
        if (IDC_EFFECTS > 0.5 && mod(floor(idcCorner.y * 24.0), 2.0) < 1.0) rgb *= 0.94;
        return vec4(rgb, alpha) * colorModulator;
    }
    return vec4(IDC_PALETTE[(idcData >> 5) & 31], alpha) * colorModulator;
#else
    return color;
#endif
}

#endif
