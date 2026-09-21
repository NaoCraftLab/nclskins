package com.naocraftlab.skins.runtime;

public final class ActivationCharacterGuard {
    private boolean spaceOwned;

    public void keyPressed(boolean space) {
        if (!space) reset();
    }

    public void activated(boolean space) {
        if (space) spaceOwned = true;
    }

    public boolean charTyped(int codepoint) {
        if (codepoint != ' ') reset();
        return spaceOwned && codepoint == ' ';
    }

    public void keyReleased(boolean space) {
        if (space) reset();
    }

    public void reset() {
        spaceOwned = false;
    }
}
