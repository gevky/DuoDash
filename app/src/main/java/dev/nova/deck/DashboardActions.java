package dev.nova.deck;

public interface DashboardActions {
    void setMain(int percent);
    void previewMain(int percent);
    void setUpper(int percent);
    void setBoth(int percent);
    void previewBoth(int percent);
    void setVolume(int percent);
    void setMainAudio(int percent);
    void setUpperAudio(int percent);
    void toggleAudioMute(String id);
    void setMetricMode(String id,String mode);
    void openNativeSettings(String id);
    void control(String id);
    void swap();
    void moveUp();
    void moveDown();
    void restore();
    void editControls();
    void close();
    void setup();
    void connection();
    void editControl(String id);
    void moveControl(String profileKey,String from,String to);
    void setControlWide(String profileKey,String id,boolean wide);
    boolean resizeControlSize(String profileKey,String id,boolean grow);
    void removeControl(String profileKey,String id);
    boolean restoreEditSnapshot(String profileKey,DashboardEditSnapshot snapshot);
    void replaceControl(String profileKey,String oldId,String newId);
    void replaceControlWithApp(String profileKey,String oldId,UiState.AppShortcut app);
}
