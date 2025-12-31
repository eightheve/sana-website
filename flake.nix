{
  description = "sana.doppel.moe website and backend";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
      in
      {
        packages.default = pkgs.stdenv.mkDerivation {
          pname = "sana-website";
          version = "0.1.0";
          src = ./.;

          nativeBuildInputs = [ ];
        };
      }
    ) // {
      nixosModules.default = { config, lib, pkgs, ... }:
        let
          cfg = config.services.sana-moe;
        in
        {
          options.services.sana-moe = {
            enable = lib.mkEnableOption "sana website and backend";

            domain = lib.mkOption {
              type = lib.types.str;
              default = "doppel.moe";
              description = "Base domain";
            };

            user = lib.mkOption {
              type = lib.types.str;
              default = "sana-helper";
              description = "User to run services as";
            };

            subDomain = lib.mkOption {
              type = lib.types.str;
              default = "sana";
              description = "Subdomain label";
            };

            stateDir = lib.mkOption {
              type = lib.types.path;
              default = "/var/lib/sana-moe";
              description = "State directory for database and persistent data";
            };

            envFile = lib.mkOption {
              type = lib.types.path;
              description = "Path to environment file containing API keys";
            };

            localPort = lib.mkOption {
              type = lib.types.int;
              default = 3200;
              description = "The port for the local backend to run on";
            };
          };

          config = lib.mkIf cfg.enable {
            users.users.${cfg.user} = {
              isSystemUser = true;
              group = "users";
              home = cfg.stateDir;
              createHome = true;
            };

            services.nginx = {
              enable = true;
              virtualHosts = {
                "${cfg.subDomain}.${cfg.domain}" = {
                  forceSSL = true;
                  enableACME = true;
                  root = "${self.packages.${pkgs.system}.default}";
                  locations."/" = {
                    proxyPass = "https://localhost:${toString cfg.localPort}/";
                    proxyWebsockets = true;
                    extraConfig = ''
                      proxy_set_header X-Real-IP $remote_addr;
                      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
                      proxy_set_header X-Forwarded-Proto $scheme;
                      
                      add_header 'Access-Control-Allow-Origin' 'https://${cfg.subDomain}.${cfg.domain}' always;
                      add_header 'Access-Control-Allow-Methods' 'GET, OPTIONS, POST, PUT, DELETE' always;
                      add_header 'Access-Control-Allow-Headers' 'Content-Type' always;
                    '';
                  };
                };
              };
            };

            systemd.services.sana-backend = {
              wantedBy = [ "multi-user.target" ];
              after = [ "network.target" ];
              description = "Backend for ${cfg.user}.${cfg.domain}";
              
              serviceConfig = {
                Type = "simple";
                User = cfg.user;
                WorkingDirectory = "${self}/backend";
                EnvironmentFile = cfg.envFile;
                Environment = ''
                  DB_PATH=${cfg.stateDir}/similar-songs.db"
                  PORT=${toString cfg.localPort}
                '';
                ExecStart = "${pkgs.clojure}/bin/clojure -M:run";
                Restart = "on-failure";
                RestartSec = "10";
                StateDirectory = "sana";
              };
            };
            environment.systemPackages = [ pkgs.clojure ];
          };
        };
    };
}

