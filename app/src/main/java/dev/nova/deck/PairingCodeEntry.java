package dev.nova.deck;

/** In-memory-only keypad buffer for the temporary Android Wireless debugging code. */
final class PairingCodeEntry {
    private final StringBuilder code = new StringBuilder(6);
    private final StringBuilder port = new StringBuilder(5);
    private boolean editingPort;

    boolean editingPort() { return editingPort; }
    void setEditingPort(boolean value) { editingPort = value; }
    String codeText() { return code.toString(); }
    String portText() { return port.toString(); }

    boolean appendDigit(char digit) {
        if (digit < '0' || digit > '9') return false;
        StringBuilder target = editingPort ? port : code;
        int limit = editingPort ? 5 : 6;
        if (target.length() >= limit) return false;
        target.append(digit);
        return true;
    }

    void backspace() {
        StringBuilder target = editingPort ? port : code;
        if (target.length() > 0) target.deleteCharAt(target.length() - 1);
    }

    void clearCurrent() { (editingPort ? port : code).setLength(0); }
    void clearAll() { code.setLength(0); port.setLength(0); editingPort = false; }

    boolean canSubmit() {
        PairingReplyParser.Reply parsed=PairingReplyParser.parse(reply());boolean valid=parsed.valid;parsed.clear();return valid;
    }

    String reply() {
        return port.length() == 0 ? code.toString() : code + " " + port;
    }
}
