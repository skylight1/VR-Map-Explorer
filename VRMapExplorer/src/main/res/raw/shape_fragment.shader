precision mediump float;        // Set the default precision to medium. We don't need as high of a
                                // precision in the fragment shader.

varying vec3 v_Position;        // Interpolated position for this fragment.

// The entry point for our fragment shader.
void main() {
    gl_FragColor = vec4(1,0,0,1);
}