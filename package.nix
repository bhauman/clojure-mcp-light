{ lib
, stdenv
, babashka
, parinfer-rust
, makeWrapper
}:

stdenv.mkDerivation rec {
  pname = "clojure-mcp-light";
  version = "0.1.0";

  src = ./.;

  nativeBuildInputs = [ makeWrapper ];
  buildInputs = [ babashka parinfer-rust ];

  dontBuild = true;

  installPhase = ''
    runHook preInstall

    # Create installation directories
    mkdir -p $out/share/${pname}
    mkdir -p $out/bin

    # Copy source files and bb.edn
    cp -r src $out/share/${pname}/
    cp bb.edn $out/share/${pname}/

    # Create clj-paren-repair-claude-hook wrapper
    makeWrapper ${babashka}/bin/bb $out/bin/clj-paren-repair-claude-hook \
      --add-flags "-cp $out/share/${pname}/src:$out/share/${pname}/bb.edn" \
      --add-flags "-m clojure-mcp-light.hook" \
      --prefix PATH : ${lib.makeBinPath [ parinfer-rust ]}

    # Create clj-nrepl-eval wrapper
    makeWrapper ${babashka}/bin/bb $out/bin/clj-nrepl-eval \
      --add-flags "-cp $out/share/${pname}/src:$out/share/${pname}/bb.edn" \
      --add-flags "-m clojure-mcp-light.nrepl-eval" \
      --prefix PATH : ${lib.makeBinPath [ parinfer-rust ]}

    runHook postInstall
  '';

  meta = with lib; {
    description = "CLI tooling for Clojure development in Claude Code";
    homepage = "https://github.com/bhauman/clojure-mcp-light";
    license = licenses.mit; # Update if different
    maintainers = [ ];
    platforms = platforms.unix;
  };
}
