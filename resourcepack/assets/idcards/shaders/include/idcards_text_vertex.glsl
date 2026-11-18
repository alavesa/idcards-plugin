#ifndef IDCARDS_TEXT_VERTEX_GLSL
#define IDCARDS_TEXT_VERTEX_GLSL

// =====================================================================================================================
// IdCards: vertex part of the core/text override. It puts the ID card HUD where it belongs on the screen.
//
// HOW THE SERVER TRIGGERS IT. The card is the NAME of a boss bar, written in the font idcards:ui. Vanilla draws that
// name centred at the top of the screen. The plugin lays the text out so that its total width is 0 (signed space
// glyphs), which makes the pen origin exactly the screen centre: a glyph's distance from the centre is its x on the
// card. Every piece of the card has the MARKER text colour:
//
//     red   = 252
//     green = 0xB8..0xBF   (the low 3 bits are data bits 8-10)
//     blue  = data bits 0-7
//     data  = slot (bits 0-4) | palette colour (bits 5-9) | mode (bit 10: 0 = own card, 1 = inspecting another's)
//
// The slot gives the row, the scale and the glyph height (idcards_layout.glsl, generated). This function throws the
// glyph's vanilla position away and computes a new one from: slot, distance from the centre, which corner of the
// quad this vertex is. The player-head glyph (an 8 px quad showing the skin) takes the same path, scaled 6x: that is
// the photo.
//
// WHAT IT LEAVES ALONE. Only GUI text (IS_GUI) is looked at; signs, name tags, text displays and maps are compiled
// without this code. GUI text whose colour is not the marker returns at the first test with gl_Position untouched.
// The marker is 1 of 2 million colours and no vanilla text uses it; other plugins' markers differ (SuperbVehicles:
// red 253, Radio: #FEFBxx, CCTV: #0CC7xx).
//
// Varyings 7 and 8: vanilla uses 0-3, SuperbVehicles 4, Radio 5.
// =====================================================================================================================

#ifdef IS_GUI
#include <idcards:idcards_layout.glsl>
#include <idcards:idcards_config.glsl>

layout(location = 7) flat out int idcData;   // the marker's data bits, or -1 for every other text
layout(location = 8) out vec2 idcCorner;     // (0,0) top-left ... (1,1) bottom-right of the glyph quad
#endif

// Call at the END of main(): it replaces gl_Position for card glyphs and must not be overwritten afterwards.
// color: the vertex colour attribute (it is declared after this file is included, so it is passed in).
void idcards_text_vertex(vec4 color) {
#ifdef IS_GUI
    // glyph quads are emitted top-left, bottom-left, bottom-right, top-right (26.3 BakedSheetGlyph and
    // PlayerGlyphProvider both do)
    int corner = gl_VertexIndex % 4;
    vec2 k = vec2(corner >= 2 ? 1.0 : 0.0, (corner == 1 || corner == 2) ? 1.0 : 0.0);
    idcCorner = k;
    idcData = -1;
    ivec3 c = ivec3(round(color.rgb * 255.0));
    if (c.r != 252 || (c.g & 0xF8) != 0xB8) return;
    int data = ((c.g & 7) << 8) | c.b;
    int slot = data & 31;
    if (slot >= IDC_SLOTS) return;
    idcData = data;
    vec4 row = IDC_SLOT[slot];                                           // top y, scale, glyph height, anchor
    vec2 gui = vec2(2.0 / ProjMat[0][0], -2.0 / ProjMat[1][1]);          // GUI size in GUI pixels
    // x in font pixels = distance from the screen centre (rounded: the centre is a half pixel on odd widths)
    float x = floor(gl_Position.x / gl_Position.w * 0.5 * gui.x + 0.75);
    vec2 local = vec2(x * row.y, row.x + k.y * row.z * row.y);          // card pixels
    vec2 pos;
    if (row.w > 0.5) {
        pos = IDC_HINT_POS + local * IDC_HINT_SCALE;                     // screen corner
    } else if (((data >> 10) & 1) == 0) {
        float fit = clamp(floor(min(gui.x / (IDC_CARD.x + 24.0), gui.y / (IDC_CARD.y + 40.0))), 1.0, 4.0);
        float s = IDC_UI_SCALE > 0.0 ? IDC_UI_SCALE : fit;
        pos = floor((gui - IDC_CARD * s) * 0.5) + local * s;             // centred
    } else {
        float s = IDC_INSPECT_SCALE;
        // against the right edge, so the presenter in the middle of the screen stays visible
        vec2 origin = vec2(gui.x - IDC_CARD.x * s - IDC_INSPECT_OFFSET.x, floor((gui.y - IDC_CARD.y * s) * 0.5) + IDC_INSPECT_OFFSET.y);
        origin.x = max(2.0, origin.x);
        pos = origin + local * s;
    }
    vec4 clip = ProjMat * vec4(pos, 0.0, 1.0);
    gl_Position = vec4(clip.xy * gl_Position.w, gl_Position.z, gl_Position.w);
#endif
}

#endif
