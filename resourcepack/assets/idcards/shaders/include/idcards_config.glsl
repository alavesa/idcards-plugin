// Where the card HUD sits and how big it is. The plugin writes this file into the pack it builds from config.yml
// (ui.scale, inspect.*, ui.hint-*, ui.effects); the values here are the defaults of a pack built by hand.
const float IDC_UI_SCALE = 0.0;                    // own card: 0 = largest whole scale that fits the screen, else fixed
const float IDC_INSPECT_SCALE = 1.0;               // somebody else's presented card
const vec2  IDC_INSPECT_OFFSET = vec2(6.0, 0.0);   // its right edge from the screen's right edge, its centre from the middle row
const vec2  IDC_HINT_POS = vec2(6.0, 6.0);         // the "left click to close" line, from the top-left corner
const float IDC_HINT_SCALE = 1.0;
const float IDC_EFFECTS = 1.0;                     // 1 = scanlines and moving sheen on the card, 0 = flat
