#!/bin/bash
# Paper 26.3 - 8 GB min / 12 GB max RAM + tunel playit.gg
cd "$(dirname "$0")"
DIR="$(pwd)"

# ---------------------------------------------------------------
# Commit opcional de cambios antes de arrancar
# ---------------------------------------------------------------
if [ -d .git ] && [ -n "$(git status --porcelain 2>/dev/null)" ]; then
    echo "============================================================"
    echo " Hay cambios sin commitear en el repositorio."
    git status --short | head -15
    read -r -p " Hacer commit limpio de los cambios antes de continuar? [s/N] " ans
    case "$ans" in
        s|S|si|SI|y|Y)
            git add -A
            if git -c user.name="${GIT_AUTHOR_NAME:-Rutbo}" \
                   -c user.email="${GIT_AUTHOR_EMAIL:-rutbo@localhost}" \
                   commit -m "${COMMIT_MSG:-server: actualizacion automatica}" >/dev/null 2>&1; then
                echo " Commit realizado: $(git rev-parse --short HEAD)"
            else
                echo " ERROR: el commit fallo (revisa git config / identidad)."
                echo " Continuando de todos modos..."
            fi
            ;;
        *)
            echo " Continuando sin commit."
            ;;
    esac
    echo "============================================================"
fi

# ---------------------------------------------------------------
# Tunel playit.gg (acceso externo)
# ---------------------------------------------------------------
PLAYITD="$DIR/tunnel/playitd"
PLAYITCLI="$DIR/tunnel/playit-cli"
SOCK="$DIR/tunnel/playit.sock"
SECRET="$DIR/tunnel/playit.toml"
PLOG="$DIR/tunnel/playit.log"

start_playit() {
    [ -x "$PLAYITD" ] || return 0

    # ¿daemon ya corriendo?
    if ! "$PLAYITCLI" --socket-path="$SOCK" status >/dev/null 2>&1; then
        setsid nohup "$PLAYITD" --socket-path="$SOCK" --secret-path="$SECRET" \
              --log-path="$PLOG" >>"$DIR/tunnel/playitd.out" 2>&1 < /dev/null &
        sleep 2
    fi

    if [ ! -f "$SECRET" ]; then
        # Sin registrar: asegurar un proceso "setup" generando la URL de claim
        if ! pgrep -f "playit-cli.*setup" >/dev/null 2>&1; then
            : > "$DIR/tunnel/claim.log"
            setsid nohup "$PLAYITCLI" --socket-path="$SOCK" setup \
                  > "$DIR/tunnel/claim.log" 2>&1 < /dev/null &
        fi

        # Esperar a que aparezca la URL (max ~20s) y mostrarla en grande
        CLAIM_URL=""
        for _ in $(seq 1 20); do
            CLAIM_URL=$(grep -oE 'https://playit\.gg/claim/[a-zA-Z0-9]+' \
                        "$DIR/tunnel/claim.log" 2>/dev/null | head -1)
            [ -n "$CLAIM_URL" ] && break
            sleep 1
        done

        echo "============================================================"
        echo " playit.gg SIN REGISTRAR - REGISTRO REQUERIDO"
        if [ -n "$CLAIM_URL" ]; then
            echo ""
            echo "  >>> Abre esta URL en tu navegador y crea tu cuenta: <<<"
            echo ""
            echo "      $CLAIM_URL"
            echo ""
        else
            echo "  No se pudo generar la URL. Revisa:"
            echo "      tail -f $DIR/tunnel/claim.log"
        fi
        echo ""
        echo "  Despues, en https://my.playit.gg:"
        echo "    Agente > Add tunnel > TCP > localhost:25565"
        echo "    La direccion at.ply.gg que te den es la de los jugadores."
        echo ""
        echo "  Log en vivo del tunel:  tail -f $DIR/tunnel/playit.log"
        echo "============================================================"
    else
        echo "[playit] Tunel configurado. Estado:"
        "$PLAYITCLI" --socket-path="$SOCK" status 2>/dev/null | head -8
        echo " La direccion publica se ve en https://my.playit.gg (agente -> tunnels)"
        echo " Log en vivo: tail -f $DIR/tunnel/playit.log"
    fi
}

start_playit

# ---------------------------------------------------------------
# Servidor Minecraft
# ---------------------------------------------------------------
# EULA aceptada automaticamente
if [ ! -f eula.txt ]; then
    echo "eula=true" > eula.txt
fi

# Cofre inicial: instala un plugin de starter chest en plugins/
mkdir -p plugins

java -Xms8G -Xmx12G \
    -XX:+UseG1GC \
    -XX:+ParallelRefProcEnabled \
    -XX:MaxGCPauseMillis=200 \
    -XX:+UnlockExperimentalVMOptions \
    -XX:+DisableExplicitGC \
    -XX:+AlwaysPreTouch \
    -XX:G1NewSizePercent=30 \
    -XX:G1MaxNewSizePercent=40 \
    -XX:G1HeapRegionSize=8M \
    -XX:G1ReservePercent=20 \
    -XX:G1HeapWastePercent=5 \
    -XX:G1MixedGCCountTarget=4 \
    -XX:InitiatingHeapOccupancyPercent=15 \
    -XX:G1MixedGCLiveThresholdPercent=90 \
    -XX:G1RSetUpdatingPauseTimePercent=5 \
    -XX:SurvivorRatio=32 \
    -XX:+PerfDisableSharedMem \
    -XX:MaxTenuringThreshold=1 \
    -jar paper-26.3-257.jar nogui
