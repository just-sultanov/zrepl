use zed::settings::LspSettings;
use zed_extension_api::{
    self as zed, register_extension, Command, Extension, LanguageServerId, Result, Worktree,
};

const LANGUAGE_SERVER_ID: &str = "zrepl";

struct ZreplExtension;

impl Extension for ZreplExtension {
    fn new() -> Self
    where
        Self: Sized,
    {
        Self
    }

    fn language_server_command(
        &mut self,
        language_server_id: &LanguageServerId,
        worktree: &Worktree,
    ) -> Result<Command> {
        if language_server_id.as_ref() != LANGUAGE_SERVER_ID {
            return Err(format!("unknown language server: {language_server_id}"));
        }
        let binary = LspSettings::for_worktree(LANGUAGE_SERVER_ID, worktree)?
            .binary
            .ok_or("configure lsp.zrepl.binary in settings.json")?;
        let path = binary
            .path
            .ok_or("lsp.zrepl.binary.path is not set in settings.json")?;
        Ok(Command {
            command: path,
            args: binary.arguments.unwrap_or_default(),
            env: binary.env.unwrap_or_default().into_iter().collect(),
        })
    }
}

register_extension!(ZreplExtension);
