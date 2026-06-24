import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashMap;
import java.util.Random;

public class Raycaster3D extends JPanel implements MouseListener, MouseMotionListener, ActionListener, Runnable {
    private static int WIDTH = 800;
    private static int HEIGHT = 600;
    private static final int PORT = 5555;
    
    private enum GameState { MENU, PLAYING, EDITOR, LEVEL_SELECT, INVENTORY, MULTIPLAYER_MENU, CLASS_SELECT }
    private GameState gameState = GameState.MENU;
    private enum MultiplayerMode { NONE, HOST, CLIENT }
    private MultiplayerMode mpMode = MultiplayerMode.NONE;
    
    // Player class system
    private enum PlayerClass { NONE, SHOTGUNNER, BOMBER }
    private PlayerClass selectedClass = PlayerClass.NONE;
    private PlayerClass currentClass = PlayerClass.NONE;
    private PlayerClass otherClass = PlayerClass.NONE;
    
    // Class stats
    private int classBonusDamage = 0;
    private int classArmorPenalty = 0;
    private double classBonusJump = 0;
    
    // Bomber unlimited bombs with cooldown
    private double bombCooldown = 0;
    private static final double BOMB_COOLDOWN_TIME = 3.0;
    private boolean bombOnCooldown = false;
    
    // Simple explosion effect
    private ArrayList<SimpleExplosion> explosions = new ArrayList<>();
    private double cameraShake = 0;
    private double flashIntensity = 0;
    
    class SimpleExplosion {
        double x, y, z;
        double age = 0;
        double maxAge = 0.3;
        
        SimpleExplosion(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
        
        void update(double deltaTime) {
            age += deltaTime;
        }
        
        boolean isAlive() { return age < maxAge; }
        
        void draw(Graphics2D g, double screenX, double screenY, int size) {
            if (age < maxAge) {
                float alpha = (float)(1.0 - age / maxAge);
                Color outerColor = new Color(255, 80, 0, (int)(150 * alpha));
                g.setColor(outerColor);
                g.fillOval((int)(screenX - size), (int)(screenY - size), size * 2, size * 2);
                Color coreColor = new Color(255, 220, 50, (int)(200 * alpha));
                g.setColor(coreColor);
                g.fillOval((int)(screenX - size/2), (int)(screenY - size/2), size, size);
            }
        }
    }
    
    // Bomb rendering
    private BufferedImage bombTexture = null;
    private ArrayList<Bomb> bombs = new ArrayList<>();
    
    class Bomb {
        double x, y, z;
        double age = 0;
        double explosionRadius = 2.5;
        boolean exploded = false;
        boolean isFromLocalPlayer = true;
        
        Bomb(double x, double y, double z, boolean fromLocalPlayer) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.isFromLocalPlayer = fromLocalPlayer;
        }
        
        void update(double deltaTime) {
            age += deltaTime;
            if (age >= 1.5 && !exploded) {
                explode();
            }
        }
        
        void explode() {
            exploded = true;
            explosions.add(new SimpleExplosion(x, y, z));
            cameraShake = 0.25;
            flashIntensity = 0.6;
            
            if (otherConnected) {
                double dx = otherPosX - x;
                double dy = otherPosY - y;
                double dz = (otherPosZ + 0.5) - z;
                double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
                if (dist < explosionRadius) {
                    int damage = 100;
                    if (isFromLocalPlayer) {
                        sendDamageToOther(damage);
                    }
                    addDebugMessage("💥 EXPLOSION! Hit enemy! Damage: " + damage);
                }
            }
            
            if (isFromLocalPlayer) {
                double dx = posX - x;
                double dy = posY - y;
                double dz = (posZ + 0.5) - z;
                double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
                if (dist < explosionRadius) {
                    int selfDamage = (int)(50 * (1 - dist / explosionRadius));
                    playerHealth = Math.max(0, playerHealth - selfDamage);
                    addDebugMessage("💥 You took " + selfDamage + " damage from your own bomb!");
                }
            }
            addDebugMessage("💣 BOOM! Bomb exploded!");
        }
        
        boolean isAlive() { return !exploded && age < 2.0; }
    }
    
    // Fullscreen variables
    private JFrame parentFrame;
    private boolean isFullscreen = false;
    private Dimension windowedSize = new Dimension(800, 600);
    private Point windowedLocation;
    private int windowedState;
    private GraphicsDevice device;
    
    // Network
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private PrintWriter out;
    private BufferedReader in;
    private Thread networkThread;
    private boolean networkRunning = false;
    private String serverIP = "localhost";
    private JTextField ipField;
    
    // Multiplayer data
    private double otherPosX = 5.5, otherPosY = 5.5, otherPosZ = 0;
    private double otherDirX = 1.0, otherDirY = 0.0;
    private double otherPlaneX = 0.0, otherPlaneY = 0.66;
    private double otherPitch = 0;
    private int otherHealth = 100;
    private int otherAmmo = 30;
    private boolean otherConnected = false;
    private long lastNetworkUpdate = 0;
    private long lastNetworkReceive = 0;
    private long lastKeepAlive = 0;
    private static final long NETWORK_UPDATE_INTERVAL = 50;
    private static final long CONNECTION_TIMEOUT = 10000;
    private static final long KEEP_ALIVE_INTERVAL = 2000;
    
    // Debug
    private JTextArea debugArea;
    private JScrollPane debugScroll;
    
    // 3D Player textures
    private BufferedImage[] playerTextures = new BufferedImage[6];
    private BufferedImage[] otherPlayerTextures = new BufferedImage[6];
    
    private JButton startButton;
    private JButton editorButton;
    private JButton loadLevelButton;
    private JButton exitButton;
    private JButton hostButton;
    private JButton connectButton;
    private JPanel menuPanel;
    private JPanel multiplayerPanel;
    private JPanel classSelectPanel;
    
    // Inventory system
    private JPanel inventoryPanel;
    private HashMap<String, Integer> inventory = new HashMap<>();
    private HashMap<String, BufferedImage> itemTextures = new HashMap<>();
    private int selectedHotbarSlot = 0;
    private String[] hotbarItems = {"Medkit", "Ammo", "Key", "Shield"};
    private BufferedImage[] hotbarTextures = new BufferedImage[4];
    
    // Items on map
    private ArrayList<Item> items = new ArrayList<>();
    private Random random = new Random();
    
    private String[] itemNames = {"Medkit", "Ammo", "Key", "HealthPotion", "Shield", "Coin", "Gem", "Sword"};
    private String[] itemPngFiles = {"medkit.png", "ammo.png", "key.png", "health_potion.png", "shield.png", "coin.png", "gem.png", "sword.png"};
    private Color[] itemColors = {Color.RED, Color.YELLOW, Color.CYAN, Color.GREEN, Color.BLUE, Color.ORANGE, Color.MAGENTA, Color.PINK};
    
    // Level select panel
    private JPanel levelSelectPanel;
    private JTextField levelNameField;
    private JList<String> levelList;
    private DefaultListModel<String> listModel;
    
    // Editor variables
    private int selectedWallType = 1;
    private int selectedItemType = 1;
    private JPanel editorPanel;
    private JButton saveButton;
    private JButton saveAsButton;
    private JButton backButton;
    private JButton wall1Button;
    private JButton wall2Button;
    private JButton wall3Button;
    private JButton wall4Button;
    private JButton wall5Button;
    private JButton eraseButton;
    private JButton itemButton;
    private JButton randomItemsButton;
    private JButton itemTypePrevButton;
    private JButton itemTypeNextButton;
    private JLabel selectedItemLabel;
    private JLabel statusLabel;
    private JTextField levelNameEditorField;
    private int[][] editedMap;
    private String currentEditLevelName = "default";
    private boolean isPlacingItems = false;
    
    // Game variables
    private double posX = 3.5, posY = 3.5;
    private double posZ = 0.0;
    private double dirX = -1.0, dirY = 0.0;
    private double planeX = 0.0, planeY = 0.66;
    
    private boolean wPressed = false, sPressed = false;
    private boolean aPressed = false, dPressed = false;
    private boolean spacePressed = false;
    private boolean shiftPressed = false;
    private boolean bombPressed = false;
    private double moveSpeed = 0.05;
    private double sprintSpeed = 0.09;
    private double baseSpeed = 0.05;
    
    // Stamina system
    private double stamina = 100.0;
    private double maxStamina = 100.0;
    private double staminaDrainRate = 25.0;
    private double staminaRegenRate = 15.0;
    private boolean isSprinting = false;
    
    private double mouseSensitivity = 0.001;
    
    private boolean isJumping = false;
    private double jumpVelocity = 0;
    private double gravity = 0.02;
    private double jumpPower = 0.6;
    private double groundLevel = 0;
    private boolean isOnBarrel = false;
    private boolean nearBarrel = false;
    private double barrelJumpBonus = 0.15;
    private double cameraHeight = 0;
    
    private Robot robot;
    private boolean mouseLocked = true;
    private double pitch = 0;
    private double skyOffset = 50;
    
    private BufferedImage gunTexture = null;
    private BufferedImage bulletTexture = null;
    private BufferedImage barrelTexture = null;
    private double bobOffset = 0;
    private double bobSpeed = 0;
    private boolean isShooting = false;
    private int shootTimer = 0;
    
    private ArrayList<Bullet> bullets = new ArrayList<>();
    private double shootCooldown = 0;
    private final double SHOOT_DELAY = 0.15;
    
    private BufferedImage skyTexture = null;
    private BufferedImage[] wallTextures = new BufferedImage[10];
    private BufferedImage canvas;
    private Timer gameTimer;
    private String currentLevelName = "default";
    private boolean isGameRunning = false;
    private int playerHealth = 100;
    private int playerAmmo = 30;
    private int playerKeys = 0;
    private int playerCoins = 0;
    private int playerGems = 0;
    
    private int[][] worldMap = {
        {1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1},
        {1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
        {1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
        {1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
        {1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
        {1,0,0,0,0,2,2,2,2,0,0,0,0,0,0,1},
        {1,0,0,0,0,2,0,0,2,0,0,0,0,0,0,1},
        {1,0,0,0,0,2,0,0,2,0,0,0,0,0,0,1},
        {1,0,0,0,0,2,0,0,2,0,0,0,0,0,0,1},
        {1,0,0,0,0,2,2,2,2,0,0,0,0,0,0,1},
        {1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
        {1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
        {1,0,0,0,0,0,0,0,0,0,0,3,3,3,0,1},
        {1,0,0,0,0,0,0,0,0,0,0,3,4,3,0,1},
        {1,0,0,0,0,0,0,0,0,0,0,3,3,3,0,1},
        {1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1},
    };
    
    private double[] wallHeights = {1.0, 1.0, 1.0, 1.0, 1.0, 0.8};
    
    class Bullet {
        double x, y, z;
        double vx, vy, vz;
        double life = 3.0;
        double age = 0;
        boolean isFromPlayer = true;
        
        Bullet(double x, double y, double z, double dirX, double dirY, double pitch, boolean fromPlayer) {
            this.x = x;
            this.y = y;
            this.z = z + 0.5;
            this.isFromPlayer = fromPlayer;
            double speed = 20.0;
            double horizontalDist = Math.cos(pitch);
            this.vx = dirX * speed * horizontalDist;
            this.vy = dirY * speed * horizontalDist;
            this.vz = Math.sin(pitch) * speed;
        }
        
        void update(double deltaTime) {
            x += vx * deltaTime;
            y += vy * deltaTime;
            z += vz * deltaTime;
            age += deltaTime;
            int mapX = (int)x;
            int mapY = (int)y;
            if (mapX >= 0 && mapX < worldMap.length && mapY >= 0 && mapY < worldMap[0].length) {
                if (worldMap[mapX][mapY] > 0) {
                    age = life;
                }
            }
        }
        
        boolean isAlive() { return age < life; }
    }
    
    class Item {
        int x, y;
        int type;
        String name;
        BufferedImage texture;
        
        Item(int x, int y, int type) {
            this.x = x;
            this.y = y;
            this.type = type;
            this.name = itemNames[type - 1];
            this.texture = itemTextures.get(this.name);
        }
        
        String toFileString() {
            return x + "," + y + "," + type;
        }
    }
    
    public Raycaster3D(JFrame frame) {
        this.parentFrame = frame;
        this.device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        
        setLayout(new BorderLayout());
        setFocusable(true);
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        
        addMouseListener(this);
        addMouseMotionListener(this);
        
        editedMap = new int[16][16];
        copyMap(worldMap, editedMap);
        
        createMenu();
        createMultiplayerPanel();
        createClassSelectPanel();
        createLevelSelectPanel();
        createEditorPanel();
        createInventoryPanel();
        loadTextures();
        loadItemTextures();
        loadBarrelTextures();
        loadBombTexture();
        initInventory();
        
        addDefaultBarrels();
        
        try {
            robot = new Robot();
        } catch (AWTException e) {
            e.printStackTrace();
        }
        
        canvas = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        gameTimer = new Timer(16, this);
        gameTimer.start();
        
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getID() == KeyEvent.KEY_PRESSED) {
                if (e.getKeyCode() == KeyEvent.VK_F11) {
                    toggleFullscreen();
                    return true;
                }
                
                if (gameState == GameState.PLAYING && isGameRunning) {
                    handleGameKeyPress(e);
                }
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    if (gameState == GameState.PLAYING) {
                        SwingUtilities.invokeLater(() -> disconnectFromMultiplayer());
                        SwingUtilities.invokeLater(() -> returnToMenu());
                    } else if (gameState == GameState.EDITOR) {
                        SwingUtilities.invokeLater(() -> exitEditor());
                    } else if (gameState == GameState.LEVEL_SELECT) {
                        SwingUtilities.invokeLater(() -> exitLevelSelect());
                    } else if (gameState == GameState.INVENTORY) {
                        SwingUtilities.invokeLater(() -> closeInventory());
                    } else if (gameState == GameState.MULTIPLAYER_MENU) {
                        SwingUtilities.invokeLater(() -> showMenu());
                    } else if (gameState == GameState.CLASS_SELECT) {
                        SwingUtilities.invokeLater(() -> showMenu());
                    }
                }
                if (e.getKeyCode() == KeyEvent.VK_I && gameState == GameState.PLAYING && isGameRunning) {
                    openInventory();
                }
                if (gameState == GameState.PLAYING && isGameRunning) {
                    switch(e.getKeyCode()) {
                        case KeyEvent.VK_1: selectedHotbarSlot = 0; useHotbarItem(); break;
                        case KeyEvent.VK_2: selectedHotbarSlot = 1; useHotbarItem(); break;
                        case KeyEvent.VK_3: selectedHotbarSlot = 2; useHotbarItem(); break;
                        case KeyEvent.VK_4: selectedHotbarSlot = 3; useHotbarItem(); break;
                        case KeyEvent.VK_G: if (selectedClass == PlayerClass.BOMBER && !bombOnCooldown) throwBomb(); break;
                    }
                }
            } else if (e.getID() == KeyEvent.KEY_RELEASED) {
                if (gameState == GameState.PLAYING && isGameRunning) {
                    handleGameKeyRelease(e);
                }
            }
            return false;
        });
        
        showMenu();
    }
    
    private void addDebugMessage(String msg) {
        SwingUtilities.invokeLater(() -> {
            if (debugArea != null) {
                debugArea.append(msg + "\n");
                debugArea.setCaretPosition(debugArea.getDocument().getLength());
            }
            System.out.println(msg);
        });
    }
    
    private void loadBombTexture() {
        try {
            File bombFile = new File("textures/bomb.png");
            if (bombFile.exists()) {
                bombTexture = ImageIO.read(bombFile);
                if (bombTexture != null) {
                    bombTexture = resizeTexture(bombTexture, 32, 32);
                }
            }
        } catch (IOException e) {}
        if (bombTexture == null) {
            bombTexture = createDefaultBombTexture();
        }
    }
    
    private BufferedImage createDefaultBombTexture() {
        BufferedImage bomb = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bomb.createGraphics();
        g.setColor(Color.BLACK);
        g.fillOval(4, 4, 24, 24);
        g.setColor(Color.RED);
        g.fillRect(14, 0, 4, 8);
        g.setColor(Color.YELLOW);
        g.fillRect(15, 2, 2, 4);
        g.setColor(Color.GRAY);
        g.fillOval(8, 8, 16, 16);
        g.dispose();
        return bomb;
    }
    
    private void throwBomb() {
        if (!bombOnCooldown && selectedClass == PlayerClass.BOMBER) {
            bombOnCooldown = true;
            bombCooldown = BOMB_COOLDOWN_TIME;
            
            double throwX = posX + dirX * 0.8;
            double throwY = posY + dirY * 0.8;
            double throwZ = posZ + 0.5;
            
            bombs.add(new Bomb(throwX, throwY, throwZ, true));
            addDebugMessage("💣 BOMB THROWN! Cooldown: 3.0 seconds");
            
            if (otherConnected && out != null) {
                out.println("BOMB:" + throwX + "," + throwY + "," + throwZ);
            }
        }
    }
    
    private void applyClassBonuses() {
        switch(selectedClass) {
            case SHOTGUNNER:
                classBonusDamage = 15;
                classArmorPenalty = 10;
                classBonusJump = 0.1;
                playerHealth = 100 - classArmorPenalty;
                jumpPower = 0.6 + classBonusJump;
                addDebugMessage("=== 🔫 SHOTGUNNER CLASS SELECTED ===");
                break;
            case BOMBER:
                addDebugMessage("=== 💣 BOMBER CLASS SELECTED ===");
                addDebugMessage("Press G to throw bomb!");
                break;
            default:
                break;
        }
        currentClass = selectedClass;
        load3DPlayerTextures();
    }
    
    private void createClassSelectPanel() {
        classSelectPanel = new JPanel(new GridBagLayout());
        classSelectPanel.setBackground(new Color(20, 20, 40));
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.insets = new Insets(10, 20, 10, 20);
        
        JLabel titleLabel = new JLabel("SELECT YOUR CLASS");
        titleLabel.setFont(new Font("Arial", Font.BOLD, 48));
        titleLabel.setForeground(Color.WHITE);
        classSelectPanel.add(titleLabel, gbc);
        
        JPanel shotgunnerPanel = new JPanel(new BorderLayout());
        shotgunnerPanel.setBackground(new Color(40, 20, 20));
        shotgunnerPanel.setBorder(BorderFactory.createLineBorder(Color.RED, 3));
        shotgunnerPanel.setPreferredSize(new Dimension(300, 200));
        
        JLabel shotgunnerTitle = new JLabel("🔫 SHOTGUNNER", SwingConstants.CENTER);
        shotgunnerTitle.setFont(new Font("Arial", Font.BOLD, 24));
        shotgunnerTitle.setForeground(Color.RED);
        shotgunnerPanel.add(shotgunnerTitle, BorderLayout.NORTH);
        
        JTextArea shotgunnerDesc = new JTextArea();
        shotgunnerDesc.setText("+15 DAMAGE\n-10 ARMOR\n+10 JUMP POWER\n5 pellets per shot!");
        shotgunnerDesc.setBackground(new Color(40, 20, 20));
        shotgunnerDesc.setForeground(Color.WHITE);
        shotgunnerDesc.setEditable(false);
        shotgunnerPanel.add(shotgunnerDesc, BorderLayout.CENTER);
        
        JButton shotgunnerButton = new JButton("SELECT");
        shotgunnerButton.setBackground(new Color(180, 50, 50));
        shotgunnerButton.setForeground(Color.WHITE);
        shotgunnerButton.addActionListener(e -> {
            selectedClass = PlayerClass.SHOTGUNNER;
            proceedToGame();
        });
        shotgunnerPanel.add(shotgunnerButton, BorderLayout.SOUTH);
        
        JPanel bomberPanel = new JPanel(new BorderLayout());
        bomberPanel.setBackground(new Color(20, 20, 40));
        bomberPanel.setBorder(BorderFactory.createLineBorder(Color.YELLOW, 3));
        bomberPanel.setPreferredSize(new Dimension(300, 200));
        
        JLabel bomberTitle = new JLabel("💣 BOMBER", SwingConstants.CENTER);
        bomberTitle.setFont(new Font("Arial", Font.BOLD, 24));
        bomberTitle.setForeground(Color.YELLOW);
        bomberPanel.add(bomberTitle, BorderLayout.NORTH);
        
        JTextArea bomberDesc = new JTextArea();
        bomberDesc.setText("UNLIMITED BOMBS!\n100 DAMAGE\n3 sec cooldown\nPress G to throw!");
        bomberDesc.setBackground(new Color(20, 20, 40));
        bomberDesc.setForeground(Color.WHITE);
        bomberDesc.setEditable(false);
        bomberPanel.add(bomberDesc, BorderLayout.CENTER);
        
        JButton bomberButton = new JButton("SELECT");
        bomberButton.setBackground(new Color(180, 150, 50));
        bomberButton.setForeground(Color.BLACK);
        bomberButton.addActionListener(e -> {
            selectedClass = PlayerClass.BOMBER;
            proceedToGame();
        });
        bomberPanel.add(bomberButton, BorderLayout.SOUTH);
        
        JPanel classesPanel = new JPanel(new GridLayout(1, 2, 20, 0));
        classesPanel.setBackground(new Color(20, 20, 40));
        classesPanel.add(shotgunnerPanel);
        classesPanel.add(bomberPanel);
        classSelectPanel.add(classesPanel, gbc);
        
        JButton backButton = new JButton("BACK TO MENU");
        backButton.setBackground(new Color(150, 50, 50));
        backButton.setForeground(Color.WHITE);
        backButton.addActionListener(e -> showMenu());
        classSelectPanel.add(backButton, gbc);
    }
    
    private void proceedToGame() {
        applyClassBonuses();
        
        if (mpMode == MultiplayerMode.NONE) {
            startGame();
        } else if (mpMode == MultiplayerMode.HOST) {
            startHost();
            startGame();
        } else {
            startClient();
            startGame();
        }
    }
    
    private void toggleFullscreen() {
        SwingUtilities.invokeLater(() -> {
            if (isFullscreen) {
                device.setFullScreenWindow(null);
                parentFrame.dispose();
                parentFrame.setUndecorated(false);
                parentFrame.setVisible(true);
                parentFrame.setSize(windowedSize);
                parentFrame.setLocation(windowedLocation);
                parentFrame.setExtendedState(windowedState);
                isFullscreen = false;
                WIDTH = windowedSize.width;
                HEIGHT = windowedSize.height;
            } else {
                windowedLocation = parentFrame.getLocation();
                windowedSize = parentFrame.getSize();
                windowedState = parentFrame.getExtendedState();
                
                parentFrame.dispose();
                parentFrame.setUndecorated(true);
                parentFrame.setVisible(true);
                
                if (device.isFullScreenSupported()) {
                    device.setFullScreenWindow(parentFrame);
                    WIDTH = parentFrame.getWidth();
                    HEIGHT = parentFrame.getHeight();
                } else {
                    parentFrame.setExtendedState(JFrame.MAXIMIZED_BOTH);
                    WIDTH = Toolkit.getDefaultToolkit().getScreenSize().width;
                    HEIGHT = Toolkit.getDefaultToolkit().getScreenSize().height;
                }
                isFullscreen = true;
            }
            
            canvas = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            setPreferredSize(new Dimension(WIDTH, HEIGHT));
            parentFrame.pack();
            revalidate();
            repaint();
            
            if (mouseLocked && isGameRunning && gameState == GameState.PLAYING) {
                centerMouse();
            }
        });
    }
    
    private void load3DPlayerTextures() {
        Color playerColor = (selectedClass == PlayerClass.SHOTGUNNER) ? new Color(180, 50, 50) : 
                           (selectedClass == PlayerClass.BOMBER) ? new Color(50, 50, 180) : Color.RED;
        
        for (int i = 0; i < 6; i++) {
            playerTextures[i] = createPlayerTexture(playerColor, i);
        }
        
        Color otherColor = (otherClass == PlayerClass.SHOTGUNNER) ? new Color(180, 50, 50) :
                          (otherClass == PlayerClass.BOMBER) ? new Color(50, 50, 180) : Color.RED;
        for (int i = 0; i < 6; i++) {
            otherPlayerTextures[i] = createPlayerTexture(otherColor, i);
        }
    }
    
    private BufferedImage createPlayerTexture(Color color, int side) {
        BufferedImage tex = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = tex.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 64, 64);
        g.setColor(Color.WHITE);
        if (side == 0) {
            g.fillOval(16, 16, 12, 12);
            g.fillOval(36, 16, 12, 12);
            g.setColor(Color.BLACK);
            g.fillOval(19, 19, 6, 6);
            g.fillOval(39, 19, 6, 6);
            g.setColor(new Color(100, 50, 50));
            g.fillArc(24, 32, 16, 12, 0, -180);
        }
        g.setColor(Color.WHITE);
        g.drawLine(0, 32, 64, 32);
        g.drawLine(32, 0, 32, 64);
        g.dispose();
        return tex;
    }
    
    private void draw3DPlayer(Graphics2D g, double x, double y, double z, double dirX, double dirY, 
                                double pitchAngle, BufferedImage[] textures, int health) {
        // Simplified 3D player rendering - just a colored box with health bar
        double dx = x - posX;
        double dy = y - posY;
        double dz = z + 0.5 - (posZ + 0.5);
        
        double invDet = 1.0 / (planeX * dirY - dirX * planeY);
        double transformX = invDet * (dy * dirX - dx * dirY);
        double transformY = invDet * (-dy * planeX + dx * planeY);
        
        if (transformY > 0.1) {
            int screenX = (int)((WIDTH / 2) * (1 + transformX / transformY));
            int screenY = (int)((HEIGHT / 2) * (1 - (dz / transformY)) + (pitch * HEIGHT * 0.8));
            int size = (int)(60 / transformY);
            
            if (size > 10 && size < 200) {
                int drawX = screenX - size / 2;
                int drawY = screenY - size / 2;
                
                // Draw player body
                g.setColor((otherClass == PlayerClass.SHOTGUNNER) ? new Color(180, 50, 50) : 
                          (otherClass == PlayerClass.BOMBER) ? new Color(50, 50, 180) : Color.RED);
                g.fillRect(drawX, drawY, size, size);
                g.setColor(Color.WHITE);
                g.drawRect(drawX, drawY, size, size);
                
                // Draw class letter
                String letter = (otherClass == PlayerClass.SHOTGUNNER) ? "D" : 
                               (otherClass == PlayerClass.BOMBER) ? "B" : "?";
                g.setColor(Color.YELLOW);
                g.setFont(new Font("Arial", Font.BOLD, size / 2));
                FontMetrics fm = g.getFontMetrics();
                int letterWidth = fm.stringWidth(letter);
                g.drawString(letter, drawX + size/2 - letterWidth/2, drawY + size/2 + fm.getAscent()/3);
                
                // Health bar
                int barWidth = size;
                int barHeight = 6;
                g.setColor(Color.RED);
                g.fillRect(drawX, drawY - barHeight - 2, barWidth, barHeight);
                g.setColor(Color.GREEN);
                g.fillRect(drawX, drawY - barHeight - 2, barWidth * health / 100, barHeight);
            }
        }
    }
    
    private void createMultiplayerPanel() {
        multiplayerPanel = new JPanel(new BorderLayout());
        multiplayerPanel.setBackground(new Color(20, 20, 40));
        
        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBackground(new Color(20, 20, 40));
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.insets = new Insets(10, 20, 10, 20);
        
        JLabel titleLabel = new JLabel("MULTIPLAYER");
        titleLabel.setFont(new Font("Arial", Font.BOLD, 48));
        titleLabel.setForeground(Color.WHITE);
        mainPanel.add(titleLabel, gbc);
        
        hostButton = new JButton("CREATE GAME (HOST)");
        hostButton.setFont(new Font("Arial", Font.BOLD, 20));
        hostButton.setPreferredSize(new Dimension(300, 50));
        hostButton.setBackground(new Color(50, 150, 50));
        hostButton.setForeground(Color.WHITE);
        hostButton.addActionListener(e -> { mpMode = MultiplayerMode.HOST; showClassSelect(); });
        mainPanel.add(hostButton, gbc);
        
        JPanel connectPanel = new JPanel(new FlowLayout());
        connectPanel.setBackground(new Color(20, 20, 40));
        ipField = new JTextField("localhost", 15);
        ipField.setFont(new Font("Arial", Font.PLAIN, 16));
        connectPanel.add(ipField);
        
        connectButton = new JButton("CONNECT TO GAME");
        connectButton.setFont(new Font("Arial", Font.BOLD, 16));
        connectButton.setBackground(new Color(100, 100, 200));
        connectButton.setForeground(Color.WHITE);
        connectButton.addActionListener(e -> { mpMode = MultiplayerMode.CLIENT; showClassSelect(); });
        connectPanel.add(connectButton);
        
        mainPanel.add(connectPanel, gbc);
        
        JButton backButton = new JButton("BACK TO MENU");
        backButton.setFont(new Font("Arial", Font.BOLD, 18));
        backButton.setBackground(new Color(150, 50, 50));
        backButton.setForeground(Color.WHITE);
        backButton.addActionListener(e -> showMenu());
        mainPanel.add(backButton, gbc);
        
        debugArea = new JTextArea(10, 40);
        debugArea.setBackground(Color.BLACK);
        debugArea.setForeground(Color.GREEN);
        debugArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        debugArea.setEditable(false);
        debugScroll = new JScrollPane(debugArea);
        debugScroll.setBorder(BorderFactory.createTitledBorder("Network Debug"));
        debugScroll.setPreferredSize(new Dimension(600, 150));
        
        multiplayerPanel.add(mainPanel, BorderLayout.CENTER);
        multiplayerPanel.add(debugScroll, BorderLayout.SOUTH);
    }
    
    private void showClassSelect() {
        gameState = GameState.CLASS_SELECT;
        removeAll();
        add(classSelectPanel, BorderLayout.CENTER);
        revalidate();
        repaint();
        requestFocusInWindow();
    }
    
    private void startHost() {
        addDebugMessage("Starting as HOST...");
        startServer();
        startNetworkLoop();
    }
    
    private void startClient() {
        serverIP = ipField.getText().trim();
        addDebugMessage("Starting as CLIENT, connecting to: " + serverIP);
        connectToServer();
        startNetworkLoop();
    }
    
    private void startServer() {
        try {
            serverSocket = new ServerSocket(PORT);
            addDebugMessage("=== SERVER STARTED on port " + PORT + " ===");
            addDebugMessage("Your IP: " + getLocalIP());
            addDebugMessage("Waiting for client...");
            
            new Thread(() -> {
                try {
                    clientSocket = serverSocket.accept();
                    addDebugMessage("=== CLIENT CONNECTED! ===");
                    out = new PrintWriter(clientSocket.getOutputStream(), true);
                    in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    otherConnected = true;
                    lastNetworkReceive = System.currentTimeMillis();
                } catch (IOException e) {
                    addDebugMessage("Server error: " + e.getMessage());
                }
            }).start();
        } catch (IOException e) {
            addDebugMessage("Could not start server!");
            JOptionPane.showMessageDialog(this, "Could not start server!", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private String getLocalIP() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }
    
    private void connectToServer() {
        try {
            addDebugMessage("Connecting to " + serverIP + ":" + PORT + "...");
            clientSocket = new Socket(serverIP, PORT);
            out = new PrintWriter(clientSocket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            otherConnected = true;
            lastNetworkReceive = System.currentTimeMillis();
            addDebugMessage("=== CONNECTED TO HOST! ===");
        } catch (IOException e) {
            addDebugMessage("Connection failed: " + e.getMessage());
            JOptionPane.showMessageDialog(this, "Could not connect to server!", "Error", JOptionPane.ERROR_MESSAGE);
            mpMode = MultiplayerMode.NONE;
            otherConnected = false;
        }
    }
    
    private void disconnectFromMultiplayer() {
        networkRunning = false;
        try {
            if (out != null) out.close();
            if (in != null) in.close();
            if (clientSocket != null) clientSocket.close();
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {}
        otherConnected = false;
        mpMode = MultiplayerMode.NONE;
    }
    
    private void startNetworkLoop() {
        networkRunning = true;
        networkThread = new Thread(this);
        networkThread.start();
    }
    
    @Override
    public void run() {
        while (networkRunning && isGameRunning) {
            try {
                if (otherConnected && out != null) {
                    long now = System.currentTimeMillis();
                    
                    if (now - lastNetworkUpdate > NETWORK_UPDATE_INTERVAL) {
                        String data = String.format("POS:%.2f,%.2f,%.2f,%.2f,%.2f,%d,%s",
                            posX, posY, posZ, dirX, dirY, playerHealth, selectedClass.name());
                        out.println(data);
                        lastNetworkUpdate = now;
                    }
                    
                    if (now - lastKeepAlive > KEEP_ALIVE_INTERVAL) {
                        out.println("PING");
                        lastKeepAlive = now;
                    }
                    
                    if (in != null && in.ready()) {
                        String line = in.readLine();
                        if (line != null) {
                            if (line.startsWith("POS:")) {
                                String[] parts = line.substring(4).split(",");
                                if (parts.length >= 7) {
                                    otherPosX = Double.parseDouble(parts[0]);
                                    otherPosY = Double.parseDouble(parts[1]);
                                    otherPosZ = Double.parseDouble(parts[2]);
                                    otherDirX = Double.parseDouble(parts[3]);
                                    otherDirY = Double.parseDouble(parts[4]);
                                    otherHealth = Integer.parseInt(parts[5]);
                                    String otherClassStr = parts[6];
                                    try {
                                        PlayerClass newClass = PlayerClass.valueOf(otherClassStr);
                                        if (otherClass != newClass) {
                                            otherClass = newClass;
                                            updateOtherPlayerTextures();
                                            addDebugMessage("Other player class: " + otherClass);
                                        }
                                    } catch (IllegalArgumentException e) {}
                                    lastNetworkReceive = System.currentTimeMillis();
                                }
                            } else if (line.startsWith("DAMAGE:")) {
                                int damage = Integer.parseInt(line.substring(7));
                                playerHealth = Math.max(0, playerHealth - damage);
                                addDebugMessage("💥 Got damage! Health: " + playerHealth);
                                cameraShake = 0.15;
                            } else if (line.startsWith("BOMB:")) {
                                String[] parts = line.substring(5).split(",");
                                if (parts.length == 3) {
                                    double bx = Double.parseDouble(parts[0]);
                                    double by = Double.parseDouble(parts[1]);
                                    double bz = Double.parseDouble(parts[2]);
                                    bombs.add(new Bomb(bx, by, bz, false));
                                    addDebugMessage("💣 Enemy bomb thrown!");
                                }
                            } else if (line.equals("PING")) {
                                out.println("PONG");
                            } else if (line.equals("PONG")) {
                                lastNetworkReceive = System.currentTimeMillis();
                            }
                        }
                    }
                    
                    if (lastNetworkReceive > 0 && System.currentTimeMillis() - lastNetworkReceive > CONNECTION_TIMEOUT) {
                        addDebugMessage("Connection timeout - disconnected");
                        otherConnected = false;
                    }
                }
                Thread.sleep(20);
            } catch (Exception e) {
                addDebugMessage("Network error: " + e.getMessage());
                otherConnected = false;
            }
        }
    }
    
    private void sendDamageToOther(int damage) {
        if (otherConnected && out != null) {
            out.println("DAMAGE:" + damage);
        }
    }
    
    private void updateOtherPlayerTextures() {
        Color otherColor = (otherClass == PlayerClass.SHOTGUNNER) ? new Color(180, 50, 50) :
                          (otherClass == PlayerClass.BOMBER) ? new Color(50, 50, 180) : Color.RED;
        for (int i = 0; i < 6; i++) {
            otherPlayerTextures[i] = createPlayerTexture(otherColor, i);
        }
    }
    
    private void addDefaultBarrels() {
        int[][] barrelsOnMap = {
            {5,6},{5,7},{5,8},{10,5},{10,10},{7,12},{8,12},{12,3},{12,4},{3,5},{3,10}
        };
        for (int[] pos : barrelsOnMap) {
            if (pos[0] >= 0 && pos[0] < 16 && pos[1] >= 0 && pos[1] < 16 && worldMap[pos[0]][pos[1]] == 0) {
                worldMap[pos[0]][pos[1]] = 5;
            }
        }
    }
    
    private void loadBarrelTextures() {
        barrelTexture = createDefaultBarrelTexture();
        if (barrelTexture != null) {
            wallTextures[5] = barrelTexture;
        }
    }
    
    private BufferedImage createDefaultBarrelTexture() {
        BufferedImage barrel = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = barrel.createGraphics();
        g.setColor(new Color(139, 69, 19));
        g.fillRect(0, 0, 64, 64);
        g.setColor(new Color(101, 67, 33));
        for (int i = 0; i < 5; i++) {
            g.fillRect(5, i * 13 + 2, 54, 3);
        }
        g.setColor(new Color(100, 100, 100));
        g.fillRect(0, 10, 64, 4);
        g.fillRect(0, 50, 64, 4);
        g.setColor(new Color(160, 100, 40));
        g.fillOval(20, 0, 24, 10);
        g.dispose();
        return barrel;
    }
    
    private void useHotbarItem() {
        String itemName = hotbarItems[selectedHotbarSlot];
        int count = inventory.getOrDefault(itemName, 0);
        if (count > 0) {
            switch(itemName) {
                case "Medkit":
                    playerHealth = Math.min(playerHealth + 25, 100 - classArmorPenalty);
                    break;
                case "HealthPotion":
                    playerHealth = Math.min(playerHealth + 50, 100 - classArmorPenalty);
                    break;
                case "Ammo":
                    playerAmmo += 10;
                    break;
                case "Key":
                    playerKeys++;
                    break;
            }
            inventory.put(itemName, count - 1);
            updateInventoryDisplay();
        }
    }
    
    private void loadItemTextures() {
        for (int i = 0; i < itemNames.length; i++) {
            itemTextures.put(itemNames[i], createDefaultItemTexture(itemColors[i]));
        }
        for (int i = 0; i < hotbarItems.length; i++) {
            hotbarTextures[i] = itemTextures.get(hotbarItems[i]);
            if (hotbarTextures[i] == null) {
                hotbarTextures[i] = createDefaultItemTexture(Color.GRAY);
            }
        }
    }
    
    private BufferedImage createDefaultItemTexture(Color color) {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillOval(4, 4, 24, 24);
        g.setColor(Color.WHITE);
        g.fillOval(12, 12, 8, 8);
        g.dispose();
        return img;
    }
    
    private void initInventory() {
        inventory.put("Medkit", 2);
        inventory.put("Ammo", 30);
        inventory.put("Key", 0);
        inventory.put("HealthPotion", 1);
        inventory.put("Shield", 1);
        inventory.put("Coin", 0);
        inventory.put("Gem", 0);
        inventory.put("Sword", 0);
    }
    
    private void createInventoryPanel() {
        inventoryPanel = new JPanel(new BorderLayout());
        inventoryPanel.setBackground(new Color(20, 20, 40));
        
        JLabel titleLabel = new JLabel("INVENTORY");
        titleLabel.setFont(new Font("Arial", Font.BOLD, 36));
        titleLabel.setForeground(Color.WHITE);
        inventoryPanel.add(titleLabel, BorderLayout.NORTH);
        
        JPanel slotsPanel = new JPanel(new GridLayout(2, 4, 10, 10));
        slotsPanel.setBackground(new Color(20, 20, 40));
        
        for (int i = 0; i < itemNames.length; i++) {
            JPanel slot = new JPanel(new BorderLayout());
            slot.setBackground(new Color(60, 60, 80));
            slot.setBorder(BorderFactory.createLineBorder(Color.WHITE, 2));
            
            JLabel itemLabel = new JLabel(itemNames[i], SwingConstants.CENTER);
            itemLabel.setForeground(Color.WHITE);
            
            JLabel countLabel = new JLabel("0", SwingConstants.CENTER);
            countLabel.setForeground(Color.YELLOW);
            
            slot.add(itemLabel, BorderLayout.CENTER);
            slot.add(countLabel, BorderLayout.SOUTH);
            
            inventorySlots[i] = countLabel;
            slotsPanel.add(slot);
        }
        
        inventoryPanel.add(slotsPanel, BorderLayout.CENTER);
        
        JPanel bottomPanel = new JPanel(new FlowLayout());
        JLabel controlsLabel = new JLabel("Press 1-4 to use items | I to close | ESC to close");
        controlsLabel.setForeground(Color.LIGHT_GRAY);
        bottomPanel.add(controlsLabel);
        inventoryPanel.add(bottomPanel, BorderLayout.SOUTH);
        
        updateInventoryDisplay();
    }
    
    private JLabel[] inventorySlots = new JLabel[8];
    
    private void updateInventoryDisplay() {
        for (int i = 0; i < inventorySlots.length && i < itemNames.length; i++) {
            if (inventorySlots[i] != null) {
                inventorySlots[i].setText(String.valueOf(inventory.getOrDefault(itemNames[i], 0)));
            }
        }
    }
    
    private void openInventory() {
        gameState = GameState.INVENTORY;
        mouseLocked = false;
        setCursor(Cursor.getDefaultCursor());
        updateInventoryDisplay();
        removeAll();
        add(inventoryPanel, BorderLayout.CENTER);
        revalidate();
        repaint();
        requestFocusInWindow();
    }
    
    private void closeInventory() {
        gameState = GameState.PLAYING;
        mouseLocked = true;
        setCursor(getToolkit().createCustomCursor(
            new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
            new Point(0, 0), "null"));
        removeAll();
        revalidate();
        repaint();
        centerMouse();
        requestFocusInWindow();
    }
    
    private void pickUpItem(int x, int y) {
        for (Iterator<Item> it = items.iterator(); it.hasNext(); ) {
            Item item = it.next();
            if (item.x == x && item.y == y) {
                String itemName = item.name;
                inventory.put(itemName, inventory.getOrDefault(itemName, 0) + 1);
                it.remove();
                
                switch(itemName) {
                    case "Medkit":
                    case "HealthPotion":
                        playerHealth = Math.min(playerHealth + 25, 100 - classArmorPenalty);
                        break;
                    case "Ammo":
                        playerAmmo += 10;
                        break;
                    case "Key":
                        playerKeys++;
                        break;
                    case "Coin":
                        playerCoins++;
                        break;
                    case "Gem":
                        playerGems++;
                        break;
                }
                updateInventoryDisplay();
                break;
            }
        }
    }
    
    private void generateRandomItems() {
        items.clear();
        int itemCount = 10 + random.nextInt(20);
        for (int i = 0; i < itemCount; i++) {
            int x, y;
            do {
                x = 1 + random.nextInt(14);
                y = 1 + random.nextInt(14);
            } while (worldMap[x][y] != 0 || (x == 3 && y == 3));
            items.add(new Item(x, y, 1 + random.nextInt(8)));
        }
    }
    
    private void copyMap(int[][] src, int[][] dst) {
        for (int i = 0; i < src.length; i++) {
            System.arraycopy(src[i], 0, dst[i], 0, src[i].length);
        }
    }
    
    private void showMenu() {
        gameState = GameState.MENU;
        mouseLocked = false;
        isGameRunning = false;
        setCursor(Cursor.getDefaultCursor());
        disconnectFromMultiplayer();
        
        createMenu();
        removeAll();
        add(menuPanel, BorderLayout.CENTER);
        revalidate();
        repaint();
    }
    
    private void createMenu() {
        menuPanel = new JPanel(new GridBagLayout());
        menuPanel.setBackground(new Color(20, 20, 40));
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.insets = new Insets(10, 20, 10, 20);
        
        JLabel titleLabel = new JLabel("3D RAYCASTER");
        titleLabel.setFont(new Font("Arial", Font.BOLD, 48));
        titleLabel.setForeground(Color.WHITE);
        menuPanel.add(titleLabel, gbc);
        
        startButton = new JButton("SINGLEPLAYER");
        startButton.setFont(new Font("Arial", Font.BOLD, 20));
        startButton.setPreferredSize(new Dimension(250, 50));
        startButton.setBackground(new Color(50, 150, 50));
        startButton.setForeground(Color.WHITE);
        startButton.addActionListener(e -> { mpMode = MultiplayerMode.NONE; showClassSelect(); });
        menuPanel.add(startButton, gbc);
        
        JButton multiplayerMenuButton = new JButton("MULTIPLAYER");
        multiplayerMenuButton.setFont(new Font("Arial", Font.BOLD, 20));
        multiplayerMenuButton.setPreferredSize(new Dimension(250, 50));
        multiplayerMenuButton.setBackground(new Color(200, 100, 50));
        multiplayerMenuButton.setForeground(Color.WHITE);
        multiplayerMenuButton.addActionListener(e -> showMultiplayerMenu());
        menuPanel.add(multiplayerMenuButton, gbc);
        
        loadLevelButton = new JButton("LOAD LEVEL");
        loadLevelButton.setFont(new Font("Arial", Font.BOLD, 20));
        loadLevelButton.setPreferredSize(new Dimension(250, 50));
        loadLevelButton.setBackground(new Color(100, 100, 200));
        loadLevelButton.setForeground(Color.WHITE);
        loadLevelButton.addActionListener(e -> openLevelSelect());
        menuPanel.add(loadLevelButton, gbc);
        
        editorButton = new JButton("MAP EDITOR");
        editorButton.setFont(new Font("Arial", Font.BOLD, 20));
        editorButton.setPreferredSize(new Dimension(250, 50));
        editorButton.setBackground(new Color(150, 100, 50));
        editorButton.setForeground(Color.WHITE);
        editorButton.addActionListener(e -> openEditor());
        menuPanel.add(editorButton, gbc);
        
        exitButton = new JButton("EXIT");
        exitButton.setFont(new Font("Arial", Font.BOLD, 20));
        exitButton.setPreferredSize(new Dimension(250, 50));
        exitButton.setBackground(new Color(150, 50, 50));
        exitButton.setForeground(Color.WHITE);
        exitButton.addActionListener(e -> System.exit(0));
        menuPanel.add(exitButton, gbc);
        
        JLabel controlsLabel = new JLabel("<html><center>WASD - Move | SHIFT - Sprint | MOUSE - Look | SPACE - Jump<br>LEFT CLICK - Shoot | G - Throw Bomb (Bomber) | 1-4 - Use items<br>I - Inventory | E - Edit mode | ESC - Menu | F11 - Fullscreen</center></html>");
        controlsLabel.setFont(new Font("Arial", Font.PLAIN, 12));
        controlsLabel.setForeground(Color.LIGHT_GRAY);
        menuPanel.add(controlsLabel, gbc);
    }
    
    private void showMultiplayerMenu() {
        gameState = GameState.MULTIPLAYER_MENU;
        removeAll();
        add(multiplayerPanel, BorderLayout.CENTER);
        revalidate();
        repaint();
        requestFocusInWindow();
    }
    
    private void createLevelSelectPanel() {
        levelSelectPanel = new JPanel(new BorderLayout());
        levelSelectPanel.setBackground(new Color(20, 20, 40));
        
        JPanel topPanel = new JPanel(new FlowLayout());
        topPanel.setBackground(new Color(20, 20, 40));
        
        JLabel titleLabel = new JLabel("LOAD LEVEL");
        titleLabel.setFont(new Font("Arial", Font.BOLD, 24));
        titleLabel.setForeground(Color.WHITE);
        topPanel.add(titleLabel);
        
        levelNameField = new JTextField("default", 15);
        topPanel.add(levelNameField);
        
        JButton loadButton = new JButton("LOAD");
        loadButton.setBackground(new Color(50, 150, 50));
        loadButton.setForeground(Color.WHITE);
        loadButton.addActionListener(e -> {
            String name = levelNameField.getText().trim();
            if (!name.isEmpty()) loadLevelAndPlay(name);
        });
        topPanel.add(loadButton);
        
        levelSelectPanel.add(topPanel, BorderLayout.NORTH);
        
        listModel = new DefaultListModel<>();
        levelList = new JList<>(listModel);
        levelList.setBackground(new Color(40, 40, 60));
        levelList.setForeground(Color.WHITE);
        
        JScrollPane scrollPane = new JScrollPane(levelList);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Available Levels"));
        levelSelectPanel.add(scrollPane, BorderLayout.CENTER);
        
        JPanel bottomPanel = new JPanel();
        bottomPanel.setBackground(new Color(20, 20, 40));
        JButton backButton = new JButton("BACK TO MENU");
        backButton.setBackground(new Color(150, 50, 50));
        backButton.setForeground(Color.WHITE);
        backButton.addActionListener(e -> exitLevelSelect());
        bottomPanel.add(backButton);
        
        levelList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    String selected = levelList.getSelectedValue();
                    if (selected != null) loadLevelAndPlay(selected);
                }
            }
        });
        
        levelSelectPanel.add(bottomPanel, BorderLayout.SOUTH);
        refreshLevelList();
    }
    
    private void refreshLevelList() {
        listModel.clear();
        listModel.addElement("default");
    }
    
    private void loadLevelAndPlay(String levelName) {
        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                loadLevel(levelName);
                loadItems(levelName);
                return null;
            }
            @Override
            protected void done() { startGame(); }
        };
        worker.execute();
    }
    
    private void loadLevel(String levelName) {
        if (levelName.equals("default")) {
            int[][] defaultMap = {
                {1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1},
                {1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
                {1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
                {1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
                {1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
                {1,0,0,0,0,2,2,2,2,0,0,0,0,0,0,1},
                {1,0,0,0,0,2,0,0,2,0,0,0,0,0,0,1},
                {1,0,0,0,0,2,0,0,2,0,0,0,0,0,0,1},
                {1,0,0,0,0,2,0,0,2,0,0,0,0,0,0,1},
                {1,0,0,0,0,2,2,2,2,0,0,0,0,0,0,1},
                {1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
                {1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
                {1,0,0,0,0,0,0,0,0,0,0,3,3,3,0,1},
                {1,0,0,0,0,0,0,0,0,0,0,3,4,3,0,1},
                {1,0,0,0,0,0,0,0,0,0,0,3,3,3,0,1},
                {1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1},
            };
            copyMap(defaultMap, worldMap);
            addDefaultBarrels();
            currentLevelName = "default";
        }
    }
    
    private void loadItems(String levelName) {
        items.clear();
        generateRandomItems();
    }
    
    private void openLevelSelect() {
        refreshLevelList();
        gameState = GameState.LEVEL_SELECT;
        removeAll();
        add(levelSelectPanel, BorderLayout.CENTER);
        revalidate();
        repaint();
        requestFocusInWindow();
    }
    
    private void exitLevelSelect() { showMenu(); }
    
    private void createEditorPanel() {
        editorPanel = new JPanel(new BorderLayout());
        editorPanel.setBackground(new Color(30, 30, 50));
        
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(new Color(50, 50, 70));
        
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        toolbar.setBackground(new Color(50, 50, 70));
        
        levelNameEditorField = new JTextField("custom_map", 8);
        
        wall1Button = new JButton("WALL1");
        wall1Button.setBackground(new Color(160, 80, 40));
        wall1Button.addActionListener(e -> { selectedWallType = 1; isPlacingItems = false; });
        
        wall2Button = new JButton("WALL2");
        wall2Button.setBackground(new Color(80, 120, 40));
        wall2Button.addActionListener(e -> { selectedWallType = 2; isPlacingItems = false; });
        
        wall3Button = new JButton("WALL3");
        wall3Button.setBackground(new Color(200, 150, 100));
        wall3Button.addActionListener(e -> { selectedWallType = 3; isPlacingItems = false; });
        
        wall4Button = new JButton("WALL4");
        wall4Button.setBackground(new Color(101, 67, 33));
        wall4Button.addActionListener(e -> { selectedWallType = 4; isPlacingItems = false; });
        
        wall5Button = new JButton("BARREL");
        wall5Button.setBackground(new Color(139, 69, 19));
        wall5Button.addActionListener(e -> { selectedWallType = 5; isPlacingItems = false; });
        
        eraseButton = new JButton("ERASE");
        eraseButton.setBackground(Color.DARK_GRAY);
        eraseButton.addActionListener(e -> { selectedWallType = 0; isPlacingItems = false; });
        
        itemButton = new JButton("ITEM MODE");
        itemButton.setBackground(new Color(255, 200, 0));
        itemButton.addActionListener(e -> { isPlacingItems = !isPlacingItems; });
        
        saveButton = new JButton("SAVE");
        saveButton.setBackground(new Color(50, 150, 50));
        saveButton.addActionListener(e -> {
            String name = levelNameEditorField.getText().trim();
            if (!name.isEmpty()) saveMap(name);
        });
        
        backButton = new JButton("BACK");
        backButton.setBackground(new Color(150, 50, 50));
        backButton.addActionListener(e -> exitEditor());
        
        toolbar.add(new JLabel("Level:"));
        toolbar.add(levelNameEditorField);
        toolbar.add(wall1Button);
        toolbar.add(wall2Button);
        toolbar.add(wall3Button);
        toolbar.add(wall4Button);
        toolbar.add(wall5Button);
        toolbar.add(eraseButton);
        toolbar.add(itemButton);
        toolbar.add(saveButton);
        toolbar.add(backButton);
        
        statusLabel = new JLabel("WALL MODE");
        statusLabel.setForeground(Color.WHITE);
        
        topBar.add(toolbar, BorderLayout.CENTER);
        topBar.add(statusLabel, BorderLayout.SOUTH);
        editorPanel.add(topBar, BorderLayout.NORTH);
        
        EditorGridPanel gridPanel = new EditorGridPanel();
        editorPanel.add(gridPanel, BorderLayout.CENTER);
    }
    
    private void generateRandomItemsForEdit() {
        items.clear();
        for (int i = 0; i < 20; i++) {
            int x = 1 + random.nextInt(14);
            int y = 1 + random.nextInt(14);
            if (editedMap[x][y] == 0) {
                items.add(new Item(x, y, 1 + random.nextInt(8)));
            }
        }
    }
    
    private void loadMapIntoEdit(String levelName) {
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                if (i == 0 || i == 15 || j == 0 || j == 15) editedMap[i][j] = 1;
                else editedMap[i][j] = 0;
            }
        }
        currentEditLevelName = levelName;
        editorPanel.repaint();
    }
    
    private void loadItemsIntoEdit(String levelName) {
        items.clear();
    }
    
    private void saveMap(String levelName) {
        addDebugMessage("Map saved: " + levelName);
        statusLabel.setText("Saved: " + levelName);
    }
    
    private void updateStatusLabel() {
        if (isPlacingItems) statusLabel.setText("ITEM MODE");
        else statusLabel.setText("WALL MODE: " + selectedWallType);
    }
    
    class EditorGridPanel extends JPanel implements MouseListener, MouseMotionListener {
        private int cellSize = 35;
        
        EditorGridPanel() {
            setPreferredSize(new Dimension(560, 560));
            setBackground(Color.BLACK);
            addMouseListener(this);
            addMouseMotionListener(this);
        }
        
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int offsetX = (getWidth() - 16 * cellSize) / 2;
            int offsetY = (getHeight() - 16 * cellSize) / 2;
            
            for (int i = 0; i < 16; i++) {
                for (int j = 0; j < 16; j++) {
                    int x = offsetX + i * cellSize;
                    int y = offsetY + j * cellSize;
                    
                    if (editedMap[i][j] == 1) g.setColor(new Color(160, 80, 40));
                    else if (editedMap[i][j] == 2) g.setColor(new Color(80, 120, 40));
                    else if (editedMap[i][j] == 3) g.setColor(new Color(200, 150, 100));
                    else if (editedMap[i][j] == 4) g.setColor(new Color(101, 67, 33));
                    else if (editedMap[i][j] == 5) g.setColor(new Color(139, 69, 19));
                    else g.setColor(Color.DARK_GRAY);
                    g.fillRect(x, y, cellSize - 1, cellSize - 1);
                    
                    g.setColor(Color.GRAY);
                    g.drawRect(x, y, cellSize - 1, cellSize - 1);
                }
            }
            updateStatusLabel();
        }
        
        private void editCell(int x, int y) {
            int offsetX = (getWidth() - 16 * cellSize) / 2;
            int offsetY = (getHeight() - 16 * cellSize) / 2;
            int gridX = (x - offsetX) / cellSize;
            int gridY = (y - offsetY) / cellSize;
            
            if (gridX >= 0 && gridX < 16 && gridY >= 0 && gridY < 16 && gridX > 0 && gridX < 15 && gridY > 0 && gridY < 15) {
                editedMap[gridX][gridY] = selectedWallType;
                repaint();
            }
        }
        
        public void mouseClicked(MouseEvent e) { editCell(e.getX(), e.getY()); }
        public void mousePressed(MouseEvent e) {}
        public void mouseReleased(MouseEvent e) {}
        public void mouseEntered(MouseEvent e) {}
        public void mouseExited(MouseEvent e) {}
        public void mouseDragged(MouseEvent e) { editCell(e.getX(), e.getY()); }
        public void mouseMoved(MouseEvent e) {}
    }
    
    private void openEditor() {
        gameState = GameState.EDITOR;
        loadMapIntoEdit(currentLevelName);
        loadItemsIntoEdit(currentLevelName);
        levelNameEditorField.setText(currentLevelName);
        removeAll();
        add(editorPanel, BorderLayout.CENTER);
        revalidate();
        repaint();
        requestFocusInWindow();
    }
    
    private void exitEditor() { showMenu(); }
    
    private void startGame() {
        isGameRunning = true;
        gameState = GameState.PLAYING;
        mouseLocked = true;
        
        if (mpMode == MultiplayerMode.NONE) {
            posX = 3.5; posY = 3.5;
        } else if (mpMode == MultiplayerMode.HOST) {
            posX = 3.5; posY = 3.5;
            otherPosX = 10.5; otherPosY = 10.5;
        } else {
            posX = 10.5; posY = 10.5;
            otherPosX = 3.5; otherPosY = 3.5;
        }
        
        posZ = 0;
        dirX = -1.0; dirY = 0.0;
        planeX = 0.0; planeY = 0.66;
        pitch = 0;
        bullets.clear();
        bombs.clear();
        explosions.clear();
        shootCooldown = 0;
        bombCooldown = 0;
        bombOnCooldown = false;
        stamina = maxStamina;
        
        load3DPlayerTextures();
        
        removeAll();
        revalidate();
        repaint();
        
        setCursor(getToolkit().createCustomCursor(
            new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
            new Point(0, 0), "null"));
        
        SwingUtilities.invokeLater(() -> {
            requestFocusInWindow();
            centerMouse();
        });
    }
    
    private void returnToMenu() {
        isGameRunning = false;
        disconnectFromMultiplayer();
        gameState = GameState.MENU;
        mouseLocked = false;
        setCursor(Cursor.getDefaultCursor());
        selectedClass = PlayerClass.NONE;
        otherClass = PlayerClass.NONE;
        showMenu();
    }
    
    private void enterEditMode() {
        if (isGameRunning && mpMode == MultiplayerMode.NONE) {
            isGameRunning = false;
            gameState = GameState.EDITOR;
            mouseLocked = false;
            setCursor(Cursor.getDefaultCursor());
            loadMapIntoEdit(currentLevelName);
            loadItemsIntoEdit(currentLevelName);
            levelNameEditorField.setText(currentLevelName);
            removeAll();
            add(editorPanel, BorderLayout.CENTER);
            revalidate();
            repaint();
            requestFocusInWindow();
        }
    }
    
    private void handleGameKeyPress(KeyEvent e) {
        switch(e.getKeyCode()) {
            case KeyEvent.VK_W: wPressed = true; break;
            case KeyEvent.VK_S: sPressed = true; break;
            case KeyEvent.VK_A: aPressed = true; break;
            case KeyEvent.VK_D: dPressed = true; break;
            case KeyEvent.VK_SPACE: spacePressed = true; break;
            case KeyEvent.VK_SHIFT: shiftPressed = true; break;
            case KeyEvent.VK_G: if (!bombPressed && selectedClass == PlayerClass.BOMBER && !bombOnCooldown) throwBomb(); bombPressed = true; break;
            case KeyEvent.VK_E: enterEditMode(); break;
        }
    }
    
    private void handleGameKeyRelease(KeyEvent e) {
        switch(e.getKeyCode()) {
            case KeyEvent.VK_W: wPressed = false; break;
            case KeyEvent.VK_S: sPressed = false; break;
            case KeyEvent.VK_A: aPressed = false; break;
            case KeyEvent.VK_D: dPressed = false; break;
            case KeyEvent.VK_SPACE: spacePressed = false; break;
            case KeyEvent.VK_SHIFT: shiftPressed = false; break;
            case KeyEvent.VK_G: bombPressed = false; break;
        }
    }
    
    private void shoot() {
        if (shootCooldown <= 0 && isGameRunning && playerAmmo > 0) {
            shootCooldown = SHOOT_DELAY;
            shootTimer = 5;
            isShooting = true;
            playerAmmo--;
            
            int pelletCount = (selectedClass == PlayerClass.SHOTGUNNER) ? 5 : 3;
            int damageDealt = (selectedClass == PlayerClass.SHOTGUNNER) ? 20 + classBonusDamage : 20;
            
            for (int i = 0; i < pelletCount; i++) {
                double angleOffset = (i - pelletCount/2) * 0.03;
                bullets.add(new Bullet(posX, posY, posZ, dirX, dirY, pitch + angleOffset, true));
            }
            updateInventoryDisplay();
            
            if (otherConnected) {
                double dx = otherPosX - posX;
                double dy = otherPosY - posY;
                double dz = (otherPosZ + 0.5) - (posZ + 0.5);
                double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
                double dot = (dx * dirX + dy * dirY) / dist;
                if (dist < 3.0 && dot > 0.7) {
                    sendDamageToOther(damageDealt);
                    addDebugMessage("🎯 Hit other player! Damage: " + damageDealt);
                }
            }
        }
    }
    
    private void loadTextures() {
        skyTexture = createDefaultSkyTexture();
        gunTexture = createDefaultGunTexture();
        bulletTexture = createDefaultBulletTexture();
        for (int i = 1; i <= 4; i++) {
            wallTextures[i] = createDefaultWallTexture(new Color(100 + i * 30, 80, 40));
        }
    }
    
    private BufferedImage createDefaultWallTexture(Color baseColor) {
        BufferedImage texture = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics g = texture.getGraphics();
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                if ((x / 16 + y / 16) % 2 == 0) g.setColor(baseColor);
                else g.setColor(baseColor.darker());
                g.fillRect(x, y, 1, 1);
            }
        }
        g.dispose();
        return texture;
    }
    
    private BufferedImage createDefaultSkyTexture() {
        BufferedImage sky = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics g = sky.getGraphics();
        g.setColor(new Color(100, 150, 255));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.dispose();
        return sky;
    }
    
    private BufferedImage createDefaultGunTexture() {
        BufferedImage gun = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        Graphics g = gun.getGraphics();
        g.setColor(new Color(50, 50, 50));
        g.fillRect(0, 0, 300, 200);
        g.setColor(new Color(100, 70, 30));
        g.fillRect(100, 80, 100, 30);
        g.fillRect(140, 50, 20, 80);
        g.dispose();
        return gun;
    }
    
    private BufferedImage createDefaultBulletTexture() {
        BufferedImage bullet = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bullet.createGraphics();
        g.setColor(new Color(255, 100, 0));
        g.fillOval(4, 4, 24, 24);
        g.setColor(Color.YELLOW);
        g.fillOval(8, 8, 16, 16);
        g.setColor(Color.WHITE);
        g.fillOval(12, 12, 8, 8);
        g.dispose();
        return bullet;
    }
    
    private BufferedImage resizeTexture(BufferedImage original, int w, int h) {
        if (original == null) return createDefaultWallTexture(Color.GRAY);
        BufferedImage resized = new BufferedImage(w, h, original.getType());
        Graphics2D g = resized.createGraphics();
        g.drawImage(original, 0, 0, w, h, null);
        g.dispose();
        return resized;
    }
    
    private void centerMouse() {
        if (!mouseLocked || !isGameRunning) return;
        try {
            Point p = getLocationOnScreen();
            if (p.x >= 0 && p.y >= 0) {
                robot.mouseMove(p.x + WIDTH / 2, p.y + HEIGHT / 2);
            }
        } catch (Exception e) {}
    }
    
    private void updateGame() {
        if (gameState != GameState.PLAYING || !isGameRunning) return;
        
        double deltaTime = 0.016;
        if (shootCooldown > 0) shootCooldown -= deltaTime;
        
        if (cameraShake > 0) cameraShake -= deltaTime * 3;
        if (flashIntensity > 0) flashIntensity -= deltaTime * 4;
        
        if (selectedClass == PlayerClass.BOMBER && bombCooldown > 0) {
            bombCooldown -= deltaTime;
            if (bombCooldown <= 0) {
                bombOnCooldown = false;
                addDebugMessage("💣 BOMB READY!");
            }
        }
        
        if (shiftPressed && !isJumping && (wPressed || sPressed || aPressed || dPressed) && stamina > 0) {
            isSprinting = true;
            stamina -= staminaDrainRate * deltaTime;
            if (stamina < 0) stamina = 0;
        } else {
            isSprinting = false;
            if (stamina < maxStamina) stamina += staminaRegenRate * deltaTime;
        }
        
        double currentMoveSpeed = baseSpeed;
        if (isSprinting && stamina > 0 && !isJumping) currentMoveSpeed = sprintSpeed;
        
        int playerCellX = (int)posX;
        int playerCellY = (int)posY;
        double newGroundLevel = 0;
        isOnBarrel = false;
        nearBarrel = false;
        
        if (playerCellX >= 0 && playerCellX < 16 && playerCellY >= 0 && playerCellY < 16 && worldMap[playerCellX][playerCellY] == 5) {
            double barrelCenterX = playerCellX + 0.5;
            double barrelCenterY = playerCellY + 0.5;
            double distToBarrel = Math.sqrt((posX - barrelCenterX)*(posX - barrelCenterX) + (posY - barrelCenterY)*(posY - barrelCenterY));
            if (distToBarrel < 0.6) {
                newGroundLevel = wallHeights[5];
                isOnBarrel = true;
            } else if (distToBarrel < 1.0 && !isJumping) nearBarrel = true;
        }
        
        if (isJumping) {
            posZ += jumpVelocity;
            jumpVelocity -= gravity;
            if (posZ <= newGroundLevel) {
                posZ = newGroundLevel;
                isJumping = false;
                jumpVelocity = 0;
            }
        } else {
            if (posZ > newGroundLevel) posZ -= gravity;
            if (posZ < newGroundLevel) posZ = newGroundLevel;
        }
        
        bobOffset = ((wPressed || sPressed || aPressed || dPressed) && !isJumping) ? Math.sin(System.currentTimeMillis() * 0.01) * 0.03 : 0;
        
        if (shootTimer > 0) { shootTimer--; isShooting = true; }
        else isShooting = false;
        
        double currentSpeed = currentMoveSpeed * (isJumping ? 0.7 : 1.0);
        
        if (wPressed) {
            double newX = posX + dirX * currentSpeed;
            double newY = posY + dirY * currentSpeed;
            int mapX = (int)newX, mapY = (int)newY;
            if (mapX >= 0 && mapX < 16 && mapY >= 0 && mapY < 16) {
                int wallType = worldMap[mapX][mapY];
                if (wallType == 0 || (wallType == 5 && posZ > 0.7)) { posX = newX; posY = newY; }
            }
        }
        if (sPressed) {
            double newX = posX - dirX * currentSpeed;
            double newY = posY - dirY * currentSpeed;
            int mapX = (int)newX, mapY = (int)newY;
            if (mapX >= 0 && mapX < 16 && mapY >= 0 && mapY < 16) {
                int wallType = worldMap[mapX][mapY];
                if (wallType == 0 || (wallType == 5 && posZ > 0.7)) { posX = newX; posY = newY; }
            }
        }
        if (aPressed) {
            double newX = posX - planeX * currentSpeed;
            double newY = posY - planeY * currentSpeed;
            int mapX = (int)newX, mapY = (int)newY;
            if (mapX >= 0 && mapX < 16 && mapY >= 0 && mapY < 16) {
                int wallType = worldMap[mapX][mapY];
                if (wallType == 0 || (wallType == 5 && posZ > 0.7)) { posX = newX; posY = newY; }
            }
        }
        if (dPressed) {
            double newX = posX + planeX * currentSpeed;
            double newY = posY + planeY * currentSpeed;
            int mapX = (int)newX, mapY = (int)newY;
            if (mapX >= 0 && mapX < 16 && mapY >= 0 && mapY < 16) {
                int wallType = worldMap[mapX][mapY];
                if (wallType == 0 || (wallType == 5 && posZ > 0.7)) { posX = newX; posY = newY; }
            }
        }
        
        if (spacePressed && !isJumping && posZ <= newGroundLevel + 0.1) {
            isJumping = true;
            jumpVelocity = isOnBarrel ? jumpPower + barrelJumpBonus : jumpPower;
        }
        
        int playerX = (int)posX, playerY = (int)posY;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                pickUpItem(playerX + dx, playerY + dy);
        
        Iterator<Bomb> bombIt = bombs.iterator();
        while (bombIt.hasNext()) {
            Bomb bomb = bombIt.next();
            bomb.update(deltaTime);
            if (!bomb.isAlive()) bombIt.remove();
        }
        
        Iterator<SimpleExplosion> expIt = explosions.iterator();
        while (expIt.hasNext()) {
            SimpleExplosion exp = expIt.next();
            exp.update(deltaTime);
            if (!exp.isAlive()) expIt.remove();
        }
        
        Iterator<Bullet> it = bullets.iterator();
        while (it.hasNext()) {
            Bullet bullet = it.next();
            bullet.update(deltaTime);
            if (!bullet.isAlive()) it.remove();
        }
        
        if (playerHealth <= 0) {
            isGameRunning = false;
            JOptionPane.showMessageDialog(this, "You died! Game Over!", "Game Over", JOptionPane.INFORMATION_MESSAGE);
            returnToMenu();
        }
    }
    
    private void renderGame() {
        if (gameState != GameState.PLAYING || !isGameRunning) return;
        
        int shakeX = 0, shakeY = 0;
        if (cameraShake > 0) {
            shakeX = random.nextInt((int)(20 * cameraShake)) - (int)(10 * cameraShake);
            shakeY = random.nextInt((int)(20 * cameraShake)) - (int)(10 * cameraShake);
        }
        
        int pitchOffset = (int)(pitch * HEIGHT * 0.8);
        int effectiveSkyOffset = (int)(skyOffset + cameraHeight * 30 + bobOffset * 100);
        int skyHeight = HEIGHT / 2 + effectiveSkyOffset + pitchOffset;
        skyHeight = Math.max(0, Math.min(HEIGHT, skyHeight));
        
        double angle = Math.atan2(dirY, dirX);
        int skyWidth = skyTexture.getWidth();
        int offset = (int)(angle * skyWidth / (2 * Math.PI));
        
        // Draw sky
        for (int x = 0; x < WIDTH; x++) {
            int texX = (offset + x) % skyWidth;
            if (texX < 0) texX += skyWidth;
            for (int y = 0; y < skyHeight; y++) {
                int texY = (y * skyTexture.getHeight()) / HEIGHT;
                if (texY >= 0 && texY < skyTexture.getHeight()) {
                    int rgb = skyTexture.getRGB(texX, texY);
                    if (flashIntensity > 0) {
                        Color c = new Color(rgb);
                        int r = Math.min(255, c.getRed() + (int)(150 * flashIntensity));
                        int g = Math.min(255, c.getGreen() + (int)(150 * flashIntensity));
                        int b = Math.min(255, c.getBlue() + (int)(100 * flashIntensity));
                        rgb = new Color(r, g, b).getRGB();
                    }
                    int dx = x + shakeX, dy = y + shakeY;
                    if (dx >= 0 && dx < WIDTH && dy >= 0 && dy < HEIGHT) {
                        canvas.setRGB(dx, dy, rgb);
                    }
                }
            }
        }
        
        // Draw ground
        for (int x = 0; x < WIDTH; x++) {
            for (int y = skyHeight; y < HEIGHT; y++) {
                int rgb = new Color(40, 40, 40).getRGB();
                if (flashIntensity > 0) {
                    Color c = new Color(rgb);
                    int r = Math.min(255, c.getRed() + (int)(100 * flashIntensity));
                    int g = Math.min(255, c.getGreen() + (int)(100 * flashIntensity));
                    int b = Math.min(255, c.getBlue() + (int)(50 * flashIntensity));
                    rgb = new Color(r, g, b).getRGB();
                }
                int dx = x + shakeX, dy = y + shakeY;
                if (dx >= 0 && dx < WIDTH && dy >= 0 && dy < HEIGHT) {
                    canvas.setRGB(dx, dy, rgb);
                }
            }
        }
        
        Graphics2D g2d = canvas.createGraphics();
        
        // Draw bombs
        for (Bomb bomb : bombs) {
            double dx = bomb.x - posX;
            double dy = bomb.y - posY;
            double dz = bomb.z - (posZ + 0.5);
            double invDet = 1.0 / (planeX * dirY - dirX * planeY);
            double transformX = invDet * (dy * dirX - dx * dirY);
            double transformY = invDet * (-dy * planeX + dx * planeY);
            
            if (transformY > 0.1) {
                int screenX = WIDTH / 2 * (int)(1 + transformX / transformY);
                int screenY = (int)(HEIGHT / 2 * (1 - dz / transformY) + pitchOffset);
                int size = (int)(60 / transformY);
                if (size > 10 && size < 150) {
                    int drawX = screenX - size / 2 + shakeX;
                    int drawY = screenY - size / 2 + shakeY;
                    if (drawX >= 0 && drawX < WIDTH && drawY >= 0 && drawY < HEIGHT) {
                        if (bombTexture != null) {
                            g2d.drawImage(bombTexture, drawX, drawY, size, size, null);
                        } else {
                            g2d.setColor(Color.BLACK);
                            g2d.fillOval(drawX, drawY, size, size);
                        }
                    }
                }
            }
        }
        
        // Draw explosions
        for (SimpleExplosion explosion : explosions) {
            double dx = explosion.x - posX;
            double dy = explosion.y - posY;
            double dz = explosion.z - (posZ + 0.5);
            double invDet = 1.0 / (planeX * dirY - dirX * planeY);
            double transformX = invDet * (dy * dirX - dx * dirY);
            double transformY = invDet * (-dy * planeX + dx * planeY);
            
            if (transformY > 0.1) {
                int screenX = WIDTH / 2 * (int)(1 + transformX / transformY);
                int screenY = (int)(HEIGHT / 2 * (1 - dz / transformY) + pitchOffset);
                int size = (int)(100 / transformY);
                if (size > 20 && size < 300) {
                    int drawX = screenX - size / 2 + shakeX;
                    int drawY = screenY - size / 2 + shakeY;
                    explosion.draw(g2d, drawX + size/2, drawY + size/2, size);
                }
            }
        }
        
        // Draw other player
        if (otherConnected && mpMode != MultiplayerMode.NONE) {
            draw3DPlayer(g2d, otherPosX, otherPosY, otherPosZ, otherDirX, otherDirY, 
                         otherPitch, otherPlayerTextures, otherHealth);
        }
        
        // Draw items
        for (Item item : items) {
            double dx = item.x + 0.5 - posX;
            double dy = item.y + 0.5 - posY;
            double dz = 0.2 - (posZ + 0.5);
            double invDet = 1.0 / (planeX * dirY - dirX * planeY);
            double transformX = invDet * (dy * dirX - dx * dirY);
            double transformY = invDet * (-dy * planeX + dx * planeY);
            
            if (transformY > 0.1) {
                int screenX = WIDTH / 2 * (int)(1 + transformX / transformY);
                int screenY = (int)(HEIGHT / 2 * (1 - dz / transformY) + pitchOffset);
                int size = (int)(45 / transformY);
                if (size > 10 && size < 100) {
                    int drawX = screenX - size / 2 + shakeX;
                    int drawY = screenY - size / 2 + shakeY;
                    if (drawX + size > 0 && drawX < WIDTH && drawY + size > 0 && drawY < HEIGHT && item.texture != null) {
                        g2d.drawImage(item.texture, drawX, drawY, size, size, null);
                    }
                }
            }
        }
        
        // Raycasting walls
        for (int x = 0; x < WIDTH; x++) {
            double cameraX = 2 * x / (double)WIDTH - 1;
            double rayDirX = dirX + planeX * cameraX;
            double rayDirY = dirY + planeY * cameraX;
            
            int mapX = (int)posX;
            int mapY = (int)posY;
            
            double deltaDistX = Math.abs(1 / rayDirX);
            double deltaDistY = Math.abs(1 / rayDirY);
            
            int stepX, stepY;
            double sideDistX, sideDistY;
            
            if (rayDirX < 0) {
                stepX = -1;
                sideDistX = (posX - mapX) * deltaDistX;
            } else {
                stepX = 1;
                sideDistX = (mapX + 1.0 - posX) * deltaDistX;
            }
            if (rayDirY < 0) {
                stepY = -1;
                sideDistY = (posY - mapY) * deltaDistY;
            } else {
                stepY = 1;
                sideDistY = (mapY + 1.0 - posY) * deltaDistY;
            }
            
            boolean hit = false;
            int side = 0;
            while (!hit) {
                if (sideDistX < sideDistY) {
                    sideDistX += deltaDistX;
                    mapX += stepX;
                    side = 0;
                } else {
                    sideDistY += deltaDistY;
                    mapY += stepY;
                    side = 1;
                }
                if (worldMap[mapX][mapY] > 0) hit = true;
            }
            
            double perpWallDist = (side == 0) ? (sideDistX - deltaDistX) : (sideDistY - deltaDistY);
            if (perpWallDist < 0.01) perpWallDist = 0.01;
            
            int wallType = worldMap[mapX][mapY];
            double wallHeight = wallHeights[wallType];
            
            int lineHeight = (int)(HEIGHT * wallHeight / perpWallDist);
            int drawStart = -lineHeight / 2 + HEIGHT / 2 + effectiveSkyOffset + pitchOffset;
            int drawEnd = lineHeight / 2 + HEIGHT / 2 + effectiveSkyOffset + pitchOffset;
            
            drawStart = Math.max(0, drawStart);
            drawEnd = Math.min(HEIGHT - 1, drawEnd);
            if (drawStart >= HEIGHT || drawEnd < 0) continue;
            
            int texNum = wallType;
            if (texNum >= wallTextures.length || wallTextures[texNum] == null) texNum = 1;
            BufferedImage texture = wallTextures[texNum];
            if (texture == null) texture = createDefaultWallTexture(Color.GRAY);
            
            double wallX = (side == 0) ? posY + perpWallDist * rayDirY : posX + perpWallDist * rayDirX;
            wallX -= Math.floor(wallX);
            
            int texX = (int)(wallX * texture.getWidth());
            if (side == 0 && rayDirX > 0) texX = texture.getWidth() - texX - 1;
            if (side == 1 && rayDirY < 0) texX = texture.getWidth() - texX - 1;
            
            for (int y = drawStart; y < drawEnd; y++) {
                int texY = (int)(((y - drawStart) * texture.getHeight()) / (double)lineHeight);
                if (texY >= 0 && texY < texture.getHeight()) {
                    int color = texture.getRGB(texX, texY);
                    if (side == 1) {
                        Color c = new Color(color);
                        color = new Color(c.getRed() / 2, c.getGreen() / 2, c.getBlue() / 2).getRGB();
                    }
                    if (flashIntensity > 0) {
                        Color c = new Color(color);
                        int r = Math.min(255, c.getRed() + (int)(100 * flashIntensity));
                        int g = Math.min(255, c.getGreen() + (int)(100 * flashIntensity));
                        int b = Math.min(255, c.getBlue() + (int)(50 * flashIntensity));
                        color = new Color(r, g, b).getRGB();
                    }
                    int dx = x + shakeX, dy = y + shakeY;
                    if (dx >= 0 && dx < WIDTH && dy >= 0 && dy < HEIGHT) {
                        canvas.setRGB(dx, dy, color);
                    }
                }
            }
        }
        
        // Draw bullets
        for (Bullet bullet : bullets) {
            double dx = bullet.x - posX;
            double dy = bullet.y - posY;
            double dz = bullet.z - (posZ + 0.5);
            double invDet = 1.0 / (planeX * dirY - dirX * planeY);
            double transformX = invDet * (dy * dirX - dx * dirY);
            double transformY = invDet * (-dy * planeX + dx * planeY);
            
            if (transformY > 0.1) {
                int screenX = WIDTH / 2 * (int)(1 + transformX / transformY);
                int screenY = (int)(HEIGHT / 2 * (1 - dz / transformY) + pitchOffset);
                int size = (int)(50 / transformY);
                if (size > 5 && size < 100) {
                    int drawX = screenX - size / 2 + shakeX;
                    int drawY = screenY - size / 2 + shakeY;
                    if (drawX + size > 0 && drawX < WIDTH && drawY + size > 0 && drawY < HEIGHT) {
                        g2d.setColor(new Color(255, 200, 50, 150));
                        g2d.fillOval(drawX, drawY, size, size);
                        if (bulletTexture != null) {
                            g2d.drawImage(bulletTexture, drawX, drawY, size, size, null);
                        }
                    }
                }
            }
        }
        
        // Draw gun
        int gunWidth = gunTexture.getWidth();
        int gunHeight = gunTexture.getHeight();
        int gunX = (WIDTH - gunWidth) / 2 + shakeX;
        int gunY = HEIGHT - gunHeight + shakeY - 20;
        
        if (isShooting) {
            gunX += 10;
            gunY += 5;
            g2d.setColor(new Color(255, 200, 100, 200));
            g2d.fillOval(gunX + gunWidth - 30, gunY + gunHeight / 2 - 20, 60, 60);
        }
        g2d.drawImage(gunTexture, gunX, gunY, null);
        
        g2d.dispose();
    }
    
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (gameState == GameState.PLAYING && isGameRunning) {
            g.drawImage(canvas, 0, 0, WIDTH, HEIGHT, null);
            drawMinimap(g);
            drawCrosshair(g);
            drawHUD(g);
            drawHotbar(g);
            drawStaminaBar(g);
            
            if (mpMode != MultiplayerMode.NONE && otherConnected) {
                g.setColor(Color.CYAN);
                g.setFont(new Font("Arial", Font.BOLD, 12));
                g.drawString("Other HP: " + otherHealth, WIDTH - 150, 50);
                g.drawString("Other at: " + (int)otherPosX + "," + (int)otherPosY, WIDTH - 150, 70);
            }
            
            if (selectedClass == PlayerClass.BOMBER && bombOnCooldown) {
                g.setColor(Color.YELLOW);
                g.setFont(new Font("Arial", Font.BOLD, 14));
                String cdMsg = "Bomb: " + String.format("%.1f", bombCooldown) + "s";
                g.drawString(cdMsg, WIDTH / 2 - 50, HEIGHT - 70);
            } else if (selectedClass == PlayerClass.BOMBER && !bombOnCooldown) {
                g.setColor(Color.GREEN);
                g.drawString("BOMB READY! Press G", WIDTH / 2 - 80, HEIGHT - 70);
            }
        }
    }
    
    private void drawStaminaBar(Graphics g) {
        int barWidth = 100;
        int barHeight = 8;
        g.setColor(Color.DARK_GRAY);
        g.fillRect(10, 150, barWidth, barHeight);
        g.setColor(new Color(100, 200, 255));
        g.fillRect(10, 150, (int)(barWidth * stamina / maxStamina), barHeight);
        g.setColor(Color.WHITE);
        g.drawRect(10, 150, barWidth, barHeight);
    }
    
    private void drawHotbar(Graphics g) {
        int slotWidth = 64;
        int slotHeight = 64;
        int startX = WIDTH / 2 - (slotWidth * 4) / 2;
        int startY = HEIGHT - slotHeight - 10;
        
        for (int i = 0; i < 4; i++) {
            int x = startX + i * slotWidth;
            int y = startY;
            
            if (i == selectedHotbarSlot) {
                g.setColor(new Color(255, 255, 100, 200));
                g.fillRect(x, y, slotWidth - 2, slotHeight - 2);
                g.setColor(Color.YELLOW);
                g.drawRect(x, y, slotWidth - 2, slotHeight - 2);
            } else {
                g.setColor(new Color(0, 0, 0, 150));
                g.fillRect(x, y, slotWidth - 2, slotHeight - 2);
                g.setColor(Color.WHITE);
                g.drawRect(x, y, slotWidth - 2, slotHeight - 2);
            }
            
            if (hotbarTextures[i] != null) {
                g.drawImage(hotbarTextures[i], x + 16, y + 8, 32, 32, null);
            }
            
            g.setColor(Color.WHITE);
            g.setFont(new Font("Arial", Font.BOLD, 10));
            g.drawString(hotbarItems[i], x + 5, y + 55);
            
            int count = inventory.getOrDefault(hotbarItems[i], 0);
            g.setColor(Color.YELLOW);
            g.drawString("x" + count, x + 40, y + 55);
        }
    }
    
    private void drawHUD(Graphics g) {
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 14));
        g.setColor(Color.RED);
        g.fillRect(10, 30, 100, 12);
        g.setColor(Color.GREEN);
        g.fillRect(10, 30, playerHealth, 12);
        g.setColor(Color.WHITE);
        g.drawRect(10, 30, 100, 12);
        g.drawString("HP: " + playerHealth, 10, 28);
        g.drawString("Ammo: " + playerAmmo, 10, 60);
        g.drawString("Keys: " + playerKeys, 10, 80);
        g.drawString("Press I for Inventory", WIDTH - 150, HEIGHT - 20);
    }
    
    private void drawMinimap(Graphics g) {
        int size = 5;
        int offsetX = 10, offsetY = 170;
        for (int i = 0; i < worldMap.length; i++) {
            for (int j = 0; j < worldMap[i].length; j++) {
                if (worldMap[i][j] > 0) {
                    if (worldMap[i][j] == 1) g.setColor(new Color(160, 80, 40));
                    else if (worldMap[i][j] == 2) g.setColor(new Color(80, 120, 40));
                    else if (worldMap[i][j] == 5) g.setColor(new Color(139, 69, 19));
                    else g.setColor(new Color(160, 160, 80));
                    g.fillRect(offsetX + i * size, offsetY + j * size, size-1, size-1);
                }
            }
        }
        
        g.setColor(Color.GREEN);
        g.fillOval(offsetX + (int)posX * size - 2, offsetY + (int)posY * size - 2, 4, 4);
        
        if (otherConnected && mpMode != MultiplayerMode.NONE) {
            g.setColor(Color.RED);
            g.fillRect(offsetX + (int)otherPosX * size - 2, offsetY + (int)otherPosY * size - 2, 4, 4);
        }
    }
    
    private void drawCrosshair(Graphics g) {
        g.setColor(Color.WHITE);
        int cx = WIDTH / 2, cy = HEIGHT / 2;
        g.drawLine(cx - 10, cy, cx - 4, cy);
        g.drawLine(cx + 4, cy, cx + 10, cy);
        g.drawLine(cx, cy - 10, cx, cy - 4);
        g.drawLine(cx, cy + 4, cx, cy + 10);
        g.drawOval(cx - 5, cy - 5, 10, 10);
    }
    
    public void mouseMoved(MouseEvent e) {
        if (gameState != GameState.PLAYING || !mouseLocked || !isGameRunning) return;
        try {
            Point p = getLocationOnScreen();
            int cx = p.x + WIDTH / 2;
            int cy = p.y + HEIGHT / 2;
            int dx = e.getXOnScreen() - cx;
            int dy = e.getYOnScreen() - cy;
            
            if (dx != 0) {
                double oldDirX = dirX;
                dirX = dirX * Math.cos(-dx * mouseSensitivity) - dirY * Math.sin(-dx * mouseSensitivity);
                dirY = oldDirX * Math.sin(-dx * mouseSensitivity) + dirY * Math.cos(-dx * mouseSensitivity);
                double oldPlaneX = planeX;
                planeX = planeX * Math.cos(-dx * mouseSensitivity) - planeY * Math.sin(-dx * mouseSensitivity);
                planeY = oldPlaneX * Math.sin(-dx * mouseSensitivity) + planeY * Math.cos(-dx * mouseSensitivity);
            }
            if (dy != 0) {
                pitch -= dy * mouseSensitivity;
                pitch = Math.max(-1.2, Math.min(1.2, pitch));
            }
            centerMouse();
        } catch (Exception ex) {}
    }
    
    public void mousePressed(MouseEvent e) {
        if (gameState == GameState.PLAYING && e.getButton() == MouseEvent.BUTTON1 && isGameRunning) shoot();
    }
    
    public void mouseReleased(MouseEvent e) {}
    public void mouseClicked(MouseEvent e) {}
    public void mouseEntered(MouseEvent e) {}
    public void mouseExited(MouseEvent e) {}
    public void mouseDragged(MouseEvent e) {}
    
    public void actionPerformed(ActionEvent e) {
        updateGame();
        renderGame();
        repaint();
    }
    
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("3D Raycaster - Multiplayer PvP");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setResizable(true);
            Raycaster3D game = new Raycaster3D(frame);
            frame.add(game);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}