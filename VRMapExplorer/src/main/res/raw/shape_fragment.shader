precision mediump float;        // Set the default precision to medium. We don't need as high of a
                                // precision in the fragment shader.

uniform vec4 u_Color;           // A constant representing the color to return

varying vec3 v_Position;        // Interpolated position for this fragment.

// The entry point for our fragment shader.
void main() {
    gl_FragColor = u_Color;
}