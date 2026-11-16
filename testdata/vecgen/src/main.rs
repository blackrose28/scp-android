//! Generates the backup test vectors with scp-core's own serializers: `ServersFile` and
//! `AppConfigForDisk` write the settings files, `backup::snapshot_with_version` builds the Archive from
//! them (plus an in-memory credential store), and `backup::crypto::encrypt` seals it.
use scp_core::backup::{crypto::encrypt, snapshot_with_version, SecretStore};
use scp_core::models::{
    AppConfig, AppConfigForDisk, AppRuntimeConfig, BoundaryAuthKind, BoundaryConfig, BoundaryOidcLoginMode,
    ConnectionMethod, OpenVpnConfig, Server, ServerOs, ServerService, ServersFile,
};
use std::{collections::BTreeMap, fs};

struct MemStore(BTreeMap<String, String>);
impl SecretStore for MemStore {
    fn read(&mut self, account: &str) -> Result<Option<String>, String> {
        Ok(self.0.get(account).cloned())
    }
    fn write(&mut self, account: &str, value: Option<&str>) -> Result<(), String> {
        match value {
            Some(v) => self.0.insert(account.into(), v.into()),
            None => self.0.remove(account),
        };
        Ok(())
    }
}

fn main() {
    let out = std::env::args().nth(1).expect("usage: vecgen <output-dir>");
    let pass = "pässwörd-тест-密码"; // non-ASCII on purpose

    let settings = tempfile::tempdir().unwrap();
    let servers = ServersFile {
        servers: vec![
            Server {
                id: "srv-11111111-1111-1111-1111-111111111111".into(),
                name: "Direct box".into(),
                host: "10.0.0.5".into(),
                os: ServerOs::Linux,
                connection_method: ConnectionMethod::Ssh,
                ssh_port: 22,
                ssh_user: "ops".into(),
                boundary_target_id: String::new(),
                boundary_listen_port: 0,
                services: vec![],
            },
            Server {
                id: "srv-22222222-2222-2222-2222-222222222222".into(),
                name: "Via Boundary".into(),
                host: "db1.internal".into(),
                os: ServerOs::Linux,
                connection_method: ConnectionMethod::Boundary,
                ssh_port: 2222,
                ssh_user: "dba".into(),
                boundary_target_id: "ttcp_abcdef1234".into(),
                boundary_listen_port: 40022,
                services: vec![ServerService { name: "pg".into(), port: 5432 }],
            },
        ],
    };
    fs::write(settings.path().join("servers.json"), serde_json::to_vec(&servers).unwrap()).unwrap();

    let config = AppConfig {
        app: AppRuntimeConfig { default_terminal_cols: 120, default_terminal_rows: 32 },
        openvpn: OpenVpnConfig::default(),
        boundary: BoundaryConfig {
            addr: "https://boundary.example.com:9200".into(),
            auth_method_id: "amoidc_1234567890".into(),
            auth_kind: BoundaryAuthKind::Oidc,
            oidc_login_mode: BoundaryOidcLoginMode::Automated,
            oidc_username: "alice@example.com".into(),
            ..BoundaryConfig::default()
        },
    };
    fs::write(
        settings.path().join("config.json"),
        serde_json::to_vec(&AppConfigForDisk::from(&config)).unwrap(),
    )
    .unwrap();

    let mut store = MemStore(BTreeMap::from([
        ("boundary_oidc_password".to_string(), "oidc-pw".to_string()),
        ("boundary_oidc_otp_secret".to_string(), "JBSWY3DPEHPK3PXP".to_string()),
        ("openvpn_password".to_string(), "vpn-pw".to_string()),
        ("server_ssh_password:srv-11111111-1111-1111-1111-111111111111".to_string(), "ssh-pw".to_string()),
    ]));
    let archive = snapshot_with_version(settings.path(), "0.0.0-vector", &mut store).unwrap();

    fs::write(format!("{out}/backup-v1.scpbak"), encrypt(&archive, pass).unwrap()).unwrap();
    fs::write(format!("{out}/backup-v1.plain.json"), serde_json::to_vec_pretty(&archive).unwrap()).unwrap();
    fs::write(format!("{out}/backup-v1.passphrase"), pass).unwrap();
}
