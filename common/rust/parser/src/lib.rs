pub mod api;

use prelude::*;

mod jsclient;
mod wsclient;


use std::panic;
use std::ops::DerefMut;
use wasm_bindgen::prelude::*;
extern crate console_error_panic_hook;
use crate::api::IsParser;

/// Handle to a parser implementation.
///
/// Currently this component is implemented as a wrapper over parser written
/// in Scala. Depending on compilation target (native or wasm) it uses either
/// implementation provided by `wsclient` or `jsclient`.
#[derive(Shrinkwrap)]
#[shrinkwrap(mutable)]
pub struct Parser(pub Box<dyn api::IsParser>);

impl Parser {
    /// Obtains a default parser implementation.
    #[cfg(not(target_arch = "wasm32"))]
    pub fn new() -> api::Result<Parser> {
        let client = wsclient::Client::new()?;
        let parser = Box::new(client);
        Ok(Parser(parser))
    }

    /// Obtains a default parser implementation.
    #[cfg(target_arch = "wasm32")]
    pub fn new() -> api::Result<Parser> {
        let client = jsclient::Client::new()?;
        let parser = Box::new(client);
        Ok(Parser(parser))
    }

    /// Obtains a default parser implementation, panicking in case of failure.
    pub fn new_or_panic() -> Parser {
        Parser::new()
            .unwrap_or_else(|e| panic!("Failed to create a parser: {:?}", e))
    }

}

impl api::IsParser for Parser {
    fn parse(&mut self, program: String) -> api::Result<api::Ast> {
        self.deref_mut().parse(program)
    }
}