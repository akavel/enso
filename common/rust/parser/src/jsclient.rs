use crate::{api, api::IsParser};
use prelude::*;
use wasm_bindgen::prelude::*;
use js_sys::JSON;

pub type Result<T> = std::result::Result<T, Error>;

#[derive(Debug, Fail)]
pub enum Error {
   #[fail(display = "JSON (de)serialization failed: {:?}", _0)]
   JsonSerializationError(#[cause] serde_json::error::Error),
}

impl From<Error> for api::Error {
   fn from(e: Error) -> Self {
      api::interop_error(e)
   }
}

impl From<serde_json::error::Error> for Error {
   fn from(error: serde_json::error::Error) -> Self {
      Error::JsonSerializationError(error)
   }
}

#[wasm_bindgen(module = "/pkg/syntax-opt.js")]
extern "C" {

   fn parse(input: String) -> String;

}

/// Wrapper over the JS-compiled parser.
///
/// Can only be used when targeting WebAssembly.
pub struct Client {}

impl Client {
   #[allow(dead_code)]
   pub fn new() -> Result<Client> {
      Ok(Client {})
   }
}

impl IsParser for Client {
   fn parse(&mut self, _program: String) -> api::Result<api::AST> {
      Ok(parse(_program))
   }
}