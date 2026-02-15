#[cfg(not(target_arch = "wasm32"))]
// mod native;
mod sqlite;

#[cfg(not(target_arch = "wasm32"))]
pub use sqlite::{Batch, Storage, IteratorMode, Direction};

#[cfg(target_arch = "wasm32")]
mod browser;
#[cfg(target_arch = "wasm32")]
pub use browser::{Batch, CursorDirection, Storage};
