#!/bin/bash

# SBT Operations Manager
# Версия: 1.2
# Автор: Ваше Имя
# Описание: Скрипт для быстрого управления SBT-проектами

# Конфигурация
LOG_FILE="sbt_operations.log"
PROJECT_DIR=$(pwd)
SBT_CMD="sbt"
JAVA_OPTS="-Xmx2G -Xss2M"

# Цвета для терминала
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Инициализация логов
init_log() {
    echo "=== SBT Operations Log ===" > "$LOG_FILE"
    log "Session started at $(date)"
}

# Логирование
log() {
    echo "[$(date +'%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE"
}

# Проверка зависимостей
check_dependencies() {
    if ! command -v sbt &> /dev/null; then
        echo -e "${RED}Ошибка: SBT не установлен!${NC}"
        echo "Установите SBT:"
        echo "  Для macOS: brew install sbt"
        echo "  Для Linux: apt-get install sbt"
        exit 1
    fi
}

# Очистка проекта
clean_project() {
    echo -e "${BLUE}Запуск очистки проекта...${NC}"
    log "Очистка проекта"
    $SBT_CMD clean
}

# Сборка проекта
build_project() {
    echo -e "${BLUE}Компиляция проекта...${NC}"
    log "Сборка проекта"
    $SBT_CMD compile
}

# Полная пересборка
rebuild_project() {
    clean_project
    build_project
}

# Запуск приложения
run_app() {
    echo -e "${GREEN}Запуск приложения...${NC}"
    log "Запуск приложения"
    $SBT_CMD "run"
}

# Быстрый перезапуск
quick_restart() {
    echo -e "${YELLOW}Быстрый перезапуск...${NC}"
    log "Быстрый перезапуск"
    clean_project
    run_app
}

# Убить все SBT процессы
kill_sbt() {
    echo -e "${RED}Убийство SBT процессов...${NC}"
    log "Убийство SBT процессов"
    pkill -f "sbt"
}

# Главное меню
show_menu() {
    echo -e "\n${BLUE}=== SBT Operations Menu ===${NC}"
    echo "1. Быстрый запуск (clean + run)"
    echo "2. Полная пересборка (clean + compile)"
    echo "3. Только запуск"
    echo "4. Запуск тестов"
    echo "5. Обновить зависимости"
    echo "6. Убить все SBT процессы"
    echo "7. Выход"
}

# Обработка выбора
handle_choice() {
    case $1 in
        1) quick_restart ;;
        2) rebuild_project ;;
        3) run_app ;;
        4) $SBT_CMD test ;;
        5) $SBT_CMD update ;;
        6) kill_sbt ;;
        7) exit 0 ;;
        *) echo -e "${RED}Неверный выбор!${NC}" ;;
    esac
}

# Основной цикл
main() {
    check_dependencies
    init_log

    # Если есть аргументы
    if [ $# -gt 0 ]; then
        case $1 in
            "clean") clean_project ;;
            "build") build_project ;;
            "run") run_app ;;
            "restart") quick_restart ;;
            "kill") kill_sbt ;;
            *) echo -e "${RED}Неизвестная команда: $1${NC}" ;;
        esac
        exit 0
    fi

    # Интерактивный режим
    while true; do
        show_menu
        echo -e "${YELLOW}\nВыберите операцию (1-7): ${NC}"
        read -r choice
        handle_choice "$choice"
    done
}

# Запуск основной программы
main "$@"