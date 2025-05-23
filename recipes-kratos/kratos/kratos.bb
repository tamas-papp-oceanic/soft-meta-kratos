DESCRIPTION = "Kratos go appplicaton"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

#GO_APP = "kratos"
GO_APP = "soft-poseidon-kratos"
#GO_IMPORT = "git/poseidon/${GO_APP}"
GO_IMPORT = "github.com/tamas-papp-oceanic/${GO_APP}"
GO_INSTALL = "${GO_IMPORT}"

#SRC_URI = "git://git@${GO_IMPORT}.git;protocol=ssh;branch=master"
SRC_URI = "git://git@${GO_IMPORT}.git;protocol=ssh;branch=main"
SRCREV = "${AUTOREV}"

do_compile[network] = "1"

UPSTREAM_CHECK_COMMITS = "1"

DEPENDS = "bash make jq"
RDEPENDS:${PN}-dev = "bash make jq"
RDEPENDS:${PN} += "bash"
RDEPENDS:${PN} += "jq"

inherit systemd

GO_LINKSHARED = ""
GOBUILDFLAGS:remove = "-buildmode=pie"

inherit go-mod

SYSTEMD_AUTO_ENABLE:${PN} = "enable"

SYSTEMD_SERVICE:${PN} = "\
  browser.service\
  can.service\
  cpupower.service\
  kratos.service\
  product-seed.service"

FILEEXTRAPATHS:prepend = "${THISDIR}/${PN}:"

SRC_URI:append = "\
  file://browser.service\
  file://can.service\
  file://cpupower.service\
  file://kratos.service\
  file://product-seed.service\
  file://product-seed.sh"

FILES:${PN} += "\
  ${systemd_unitdir}/system/*\
  ${datadir}/kratos/*\
  ${libdir}/kratos/*"

INSANE_SKIP:${PN} += "already-stripped"
INSANE_SKIP:${PN}-dev += "dev-elf"

auto_load() {
  # Define result array
  RES=''
  # Cut "auto_load" object
  STR=$(cat ${S}/src/${GO_IMPORT}/default/kratos.json | tr -d '\n' | tr -d ' ' | tr -d '"')
  POS=$(echo $STR | grep -ob 'auto_load' | grep -oE '[0-9]+')
  POS=$(expr $POS + 12)
  TMP=$(echo "$STR" | cut -c$POS-)
  POS=$(echo "$TMP" | grep -ob ']},' | grep -oE '[0-9]+')
  POS=$(expr $POS + 1)
  OBJ=$(echo "$TMP" | cut -c-$POS)
  OBJ=$(echo $OBJ | sed -e 's/],/]|/g')
  # Split object by keys
  IFS='|'
  set -o noglob
  set -- $OBJ
  for ITM in "$@"; do
    # Extract key and value
    POS=$(echo "$ITM" | grep -obF ':[' | grep -oE '[0-9]+')
    KEY=$(echo "$ITM" | cut -c-$POS)
    POS=$(expr $POS + 3)
    VAL=$(echo "$ITM" | cut -c$POS-)
    POS=$(echo "$VAL" | grep -obF ']' | grep -oE '[0-9]+')
    VAL=$(echo "$VAL" | cut -c-$POS)
    # Split value into services
    IFS=','
    set -o noglob
    set -- $VAL
    for SRV in "$@"; do
      SCH=$(echo "$KEY"s/"$SRV")
      case $RES in
        *$SCH*)
        ;;
        *)
        RES=$(echo "$RES","$SCH")
        ;;
      esac
    done
  done
  RES=$(echo "$RES" | cut -c2-)
  echo "$RES"
}

connections() {
  # Define result array
  RES=''
  # Cut "Connections" object
  STR=$(cat ${S}/src/${GO_IMPORT}/default/connections.json | tr -d '\n' | tr -d ' ' | tr -d '"' | tr '[:upper:]' '[:lower:]')
  POS=$(echo $STR | grep -ob 'connections:' | grep -oE '[0-9]+')
  POS=$(expr $POS + 14)
  TMP=$(echo "$STR" | cut -c$POS- | sed 's/.$//g' | sed 's/.$//g')
  TMP=$(echo $TMP | sed -e 's/},{/}|{/g')
  # Split object by keys
  IFS='|'
  set -o noglob
  set -- $TMP
  for ITM in "$@"; do
    ITM=$(echo "$ITM" | cut -c2- | sed 's/.$//g')
    ITM=$(echo "$ITM" | sed -e 's/},/}|/g')
    # Split object by keys
    IFS='|'
    set -o noglob
    set -- $ITM
    for ELM in "$@"; do
      # Extract key and value
      POS=$(echo "$ELM" | grep -obF ':{' | grep -oE '[0-9]+')
      KEY=$(echo "$ELM" | cut -c-$POS)
      POS=$(expr $POS + 2)
      VAL=$(echo "$ELM" | cut -c$POS-)
      VAL=$(echo "$VAL" | cut -c2- | sed 's/.$//g')
      VAL=$(echo "$VAL" | sed 's/class://g' | sed 's/name://g' | sed 's/thread://g' | sed 's/port://g' | sed 's/schemas://g')
      VAL=$(echo "$VAL" | tr -d '[:space:]')
      # Split value into services
      IFS=','
      set -o noglob
      set -- $VAL
      SCH=$(echo "$1"s/"$2")
      case $RES in
        *$SCH*)
        ;;
        *)
          RES=$(echo "$RES","$SCH")
        ;;
      esac
    done
  done
  RES=$(echo "$RES" | cut -c2-)
  echo "$RES"
}

do_compile() {
  # BUILD MAIN
  SRC=${S}/src/${GO_IMPORT}
  DST=${S}/src/${GO_IMPORT}/out
  go build -ldflags="-s -w" -o $DST/kratos $SRC/main.go

  # BUILD MANAGERS
  SRC=${S}/src/${GO_IMPORT}/managers
  DST=${S}/src/${GO_IMPORT}/out/plugins/managers
  for f in $(find ${SRC} -mindepth 1 -maxdepth 2 -name manager.go -type f); do
    DIR=$(echo "$(basename $(dirname $f))" | tr '[:upper:]' '[:lower:]')
    go build -ldflags="-s -w" -buildmode=plugin -o ${DST}/${DIR}.so $f
  done

  # BUILD PLUGINS
  SRC=${S}/src/${GO_IMPORT}
  DST=${S}/src/${GO_IMPORT}/out/plugins
  DEF=${SRC}/default/

  # PROCESSING "kratos.json" CONFIGURATION
  AUL=$(auto_load)

  # PROCESSING "connections.json" CONFIGURATION
  CON=$(connections)
  # Add modbus processing for Nike
  if ${@bb.utils.contains('DISTRO_FEATURES', 'nike', 'true', 'false', d)}; then
    CON="$CON,sources/modsrc,translators/modbus"
  fi
  CNF=$(echo "$AUL","$CON")

  # LOOP THROUGH CONFIGURED PLUGINS & COMPILE
  IFS=','
  set -o noglob
  set -- $CNF
  for ELM in "$@"; do
    DIR=$(echo "$ELM" | cut -d'/' -f1)
    NAM=$(echo "$ELM" | cut -d'/' -f2)
    DIS=$(find $DEF -mindepth 1 -maxdepth 1 -type d)
    DIS=$(echo "$DIS" | sed -z 's/\n/,/g')
    for DI1 in $DIS; do
      FIS=$(find $DI1 -mindepth 1 -maxdepth 1 -type f)
      FIS=$(echo "$FIS" | sed -z 's/\n/,/g')
      for FIL in $FIS; do
        case "$FIL" in
          *.json)
            STR=$(cat $FIL | tr -d '\n' | tr -d ' ' | tr -d '"' | tr '[:upper:]' '[:lower:]')
            IFS=','
            set -o noglob
            set -- $STR
            for SUB in "$@"; do
              case "$SUB" in
                *module:*)
                  MOD=$(echo "$SUB" | sed 's/{module://')
                  if [ "$MOD" = "$NAM" ]; then
                    # Module name equal with definition
                    DI2=$(find $SRC/$DIR -mindepth 1 -maxdepth 1 -type d)
                    DI2=$(echo "$DI2" | sed -z 's/\n/,/g')
                    IFS=','
                    set -o noglob
                    set -- $DI2
                    for DI3 in "$@"; do
                      MOD=$(echo "${DI3#$SRC/$DIR/}" | tr '[:upper:]' '[:lower:]')
                      CMP=${FIL#$DEF$DIR/}
                      CMP=${CMP%.json}
                      if [ "$MOD" = "$CMP" ]; then
                         go build -ldflags="-s -w" -buildmode=plugin -o $DST/$DIR/$CMP.so $DI3/module.go
                      fi
                    done
                  fi
                ;;
                *)
                ;;
              esac
            done
          ;;
          *)
          ;;
        esac
      done
    done
  done
}

do_install() {
  # CREATE OUTPUT FOLDERS
  install -d --mode 755 ${D}${datadir}/kratos/cache
  install -d --mode 755 ${D}${datadir}/kratos/configs
  install -d --mode 755 ${D}${datadir}/kratos/demos
  install -d --mode 755 ${D}${libdir}/kratos
  install -d --mode 755 ${D}${libdir}/kratos/plugins
  install -d --mode 755 ${D}/${systemd_unitdir}/system

  # PROPAGATE CONFIGS
  SRC=${S}/src/${GO_IMPORT}/default/*
  DST=${D}${datadir}/kratos/configs
  cp -r --no-dereference --preserve=mode,links -v $SRC $DST
  rm $DST/kratos.json

  # PROPAGATE MAIN & CONFIG
  SRC=${S}/src/${GO_IMPORT}
  DST=${D}${libdir}/kratos
  install --mode 755 $SRC/out/kratos $DST
  cp --no-dereference --preserve=mode,links -v $SRC/default/kratos.json $DST

  # PROPAGATE PLUGINS
  SRC=${S}/src/${GO_IMPORT}/out/plugins/*
  DST=${D}${libdir}/kratos/plugins
  cp --no-dereference --preserve=mode,links -v -r $SRC $DST

  # INSTALL SERVICES
  SRC=${WORKDIR}
  install -m 0644 $SRC/*.service ${D}${systemd_unitdir}/system
  install -m 0755 $SRC/*.sh ${D}${libdir}/kratos
}
