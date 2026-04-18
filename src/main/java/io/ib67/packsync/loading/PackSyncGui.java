package io.ib67.packsync.loading;

import io.ib67.packsync.UpdateEvent;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.concurrent.atomic.AtomicInteger;

public class PackSyncGui extends JFrame implements UpdateEvent.Listener {
    private final JProgressBar progressBar;
    private final JLabel statusLabel;
    private final JLabel versionLabel;
    private final AtomicInteger completedTasks = new AtomicInteger(0);
    private int totalTasks = 0;

    public PackSyncGui() {
        setTitle("PackSync - Updating Modpack");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(500, 180);
        setLocationRelativeTo(null);
        setAlwaysOnTop(true);
        setResizable(false);

        // Styling
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(20, 20, 20, 20));
        panel.setBackground(Color.WHITE);

        statusLabel = new JLabel("Starting update check...");
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 14));

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setPreferredSize(new Dimension(460, 30));

        versionLabel = new JLabel("Checking version...");
        versionLabel.setFont(new Font("SansSerif", Font.ITALIC, 11));
        versionLabel.setForeground(Color.GRAY);

        panel.add(statusLabel, BorderLayout.NORTH);
        panel.add(progressBar, BorderLayout.CENTER);
        panel.add(versionLabel, BorderLayout.SOUTH);

        add(panel);
    }

    @Override
    public void accept(UpdateEvent event) {
        SwingUtilities.invokeLater(() -> handleEvent(event));
    }

    private void handleEvent(UpdateEvent event) {
        if (event instanceof UpdateEvent.StartUpdateCheck) {
            statusLabel.setText("Connecting to update server...");
            progressBar.setIndeterminate(true);
        } else if (event instanceof UpdateEvent.FetchManifest e) {
            versionLabel.setText("Remote Version: " + e.manifest().version());
        } else if (event instanceof UpdateEvent.FetchedTaskList e) {
            totalTasks = e.tasks().size();
            progressBar.setMaximum(totalTasks);
            progressBar.setValue(0);
            progressBar.setIndeterminate(false);
            statusLabel.setText("Analyzing files (" + totalTasks + " tasks discovered)...");
        } else if (event instanceof UpdateEvent.AboutToDownload e) {
            statusLabel.setText("Downloading: " + e.destination().getFileName());
        } else if (event instanceof UpdateEvent.FileDownloaded e) {
            int val = completedTasks.incrementAndGet();
            progressBar.setValue(val);
            if (!e.success()) {
                statusLabel.setText("Failed to download: " + e.destination().getFileName());
                statusLabel.setForeground(Color.RED);
            }
        } else if (event instanceof UpdateEvent.FileRemoved e) {
            statusLabel.setText("Removing: " + e.file().getFileName());
        } else if (event instanceof UpdateEvent.UpdateFinished e) {
            dispose();
        } else if (event instanceof UpdateEvent.SyncError e) {
            statusLabel.setText("Error: " + e.message());
            statusLabel.setForeground(Color.RED);
            progressBar.setIndeterminate(false);
            progressBar.setForeground(Color.RED);
        }
    }
}
