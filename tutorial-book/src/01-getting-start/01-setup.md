# Installation and Setup

Before we dive into building applications with WaterUI, let's set up a proper development environment. This chapter will guide you through installing Rust, setting up your editor, and creating your first WaterUI project.

## Installing Rust

WaterUI requires Rust 1.85 or later with the 2024 edition. The easiest way to install Rust is through rustup.

### On macOS, Linux, or WSL

```bash,ignore
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source ~/.cargo/env
```

### On Windows

1. Download the installer from [rustup.rs](https://rustup.rs/)
2. Run the downloaded `.exe` file
3. Follow the installation prompts
4. Restart your command prompt or PowerShell

### Verify Installation

After installation, verify that everything works:

```bash,ignore
rustc --version
cargo --version
```

You should see output like:
```text
rustc 1.85.0 (a28077b28 2024-02-28)
cargo 1.85.0 (1e91b550c 2024-02-27)
```

> **Note**: WaterUI requires Rust 1.85 or later. If you have an older version, update with `rustup update`.

## Editor Setup

While you can use any text editor, we recommend VS Code for the best WaterUI development experience.

### Visual Studio Code (Recommended)

1. **Install VS Code**: Download from [code.visualstudio.com](https://code.visualstudio.com/)

2. **Install Essential Extensions**:
   - **rust-analyzer**: Provides IntelliSense, error checking, and code completion
   - **CodeLLDB**: Debugger for Rust applications
   - **Better TOML**: Syntax highlighting for Cargo.toml files

3. **Optional Extensions**:
   - **Error Lens**: Inline error messages
   - **Bracket Pair Colorizer**: Colorizes matching brackets
   - **GitLens**: Enhanced Git integration

### Other Popular Editors

**IntelliJ IDEA / CLion**:
- Install the "Rust" plugin
- Excellent for complex projects and debugging

**Vim / Neovim**:
- Use `rust.vim` for syntax highlighting
- Use `coc-rust-analyzer` for LSP support

**Emacs**:
- Use `rust-mode` for syntax highlighting
- Use `lsp-mode` with rust-analyzer

## Creating Your First Project

Let's create a new WaterUI project from scratch:

```bash,ignore
cargo new hello-waterui
cd hello-waterui
```

This creates a new Rust project with the following structure:

```text
hello-waterui/
├── Cargo.toml
├── src/
│   └── main.rs
└── .gitignore
```

### Adding WaterUI Dependencies

Edit your `Cargo.toml` file to include WaterUI:

**Filename**: `Cargo.toml`
```toml
[package]
name = "hello-waterui"
version = "0.1.0"
edition = "2021"

[dependencies]
waterui = "0.1.0"
# Choose your backend(s)
waterui_gtk4 = "0.1.0"    # For desktop applications
# waterui_web = "0.1.0"     # For web applications
```

> **Tip**: You can include multiple backends in the same project to support different platforms.

## Platform-Specific Setup

Depending on your target platform, you may need additional system dependencies.

### Desktop Development (GTK4)

**Ubuntu/Debian**:
```bash,ignore
sudo apt update
sudo apt install libgtk-4-dev build-essential
```

**Fedora/RHEL**:
```bash,ignore
sudo dnf install gtk4-devel gcc
```

**Arch Linux**:
```bash,ignore
sudo pacman -S gtk4 base-devel
```

**macOS**:
```bash,ignore
# Install Homebrew if you haven't already
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# Install GTK4
brew install gtk4
```

**Windows**:
1. Install [MSYS2](https://www.msys2.org/)
2. Open MSYS2 terminal and run:
   ```bash,ignore
   pacman -S mingw-w64-x86_64-gtk4 mingw-w64-x86_64-toolchain
   ```
3. Add MSYS2 to your PATH: `C:\msys64\mingw64\bin`

### Web Development (WebAssembly)

For web development, install additional tools:

```bash,ignore
# Install wasm-pack for building WebAssembly packages
curl https://rustwasm.github.io/wasm-pack/installer/init.sh -sSf | sh

# Add WebAssembly target
rustup target add wasm32-unknown-unknown
```

## Hello,world!

Let's create a simple "Hello, World!" application to verify everything works.

**Filename**: `src/main.rs`
```rust,ignore
use waterui::View;
use waterui_gtk4::{Gtk4App, init};

fn home() -> impl View { "Hello, WaterUI! 🌊" }

fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Initialize the GTK4 backend
    init()?;

    // Create and run the application
    let app = Gtk4App::new("com.example.hello-waterui");
    Ok(app.run(home).into())
}
```

### Building and Running

Build and run your application:

```bash,ignore
cargo run
```

If everything is set up correctly, you should see a window with "Hello, WaterUI! 🌊" displayed.

## Troubleshooting Common Issues

### GTK4 Not Found

**Error**: `Package 'gtk4' not found`

**Solution**: Install GTK4 development libraries for your platform (see Platform-Specific Setup above).

### Rust Version Too Old

**Error**: `error: package requires Rust version 1.85`

**Solution**: Update Rust:
```bash,ignore
rustup update
```

### Windows Build Issues

**Error**: Various Windows compilation errors

**Solutions**:
1. Ensure you have the Microsoft C++ Build Tools installed
2. Use the `x86_64-pc-windows-msvc` toolchain
3. Consider using WSL2 for a Linux-like environment
