<?php
/**
 * Plugin Name: Family Guard
 * Description: Семейная панель родительского контроля для приложения Family Guard Child. Открытая система: ребёнок всегда видит постоянное уведомление, снимок делается только после подтверждения на телефоне.
 * Version: 1.0.0
 * Requires at least: 6.2
 * Requires PHP: 7.4
 * Author: Family Guard
 */

if (!defined('ABSPATH')) {
    exit;
}

final class Family_Guard_Plugin {
    const VERSION = '1.0.0';
    const DB_VERSION = 3;
    const REST_NAMESPACE = 'family-guard/v1';
    const OPTION_PAIRING = 'family_guard_pairing';
    const OPTION_DB = 'family_guard_db_version';
    const OPTION_SETTINGS = 'family_guard_settings';
    const PRIVATE_DIR = 'family-guard-private';
    const OFFLINE_MINUTES = 20;
    const LOW_BATTERY = 15;
    const PERMISSION_LABELS = array(
        'location' => 'Геолокация',
        'background_location' => 'Геолокация в фоне',
        'usage' => 'Статистика приложений',
        'notifications' => 'Уведомления',
        'battery_unrestricted' => 'Без ограничений батареи',
    );
    // Типы событий в ленте и их понятные подписи.
    const EVENT_LABELS = array(
        'geofence_enter' => 'Вошёл в зону',
        'geofence_exit' => 'Вышел из зоны',
        'low_battery' => 'Низкий заряд',
        'limit_exceeded' => 'Превышен лимит экрана',
        'sos' => 'Сигнал SOS',
        'new_app' => 'Новое приложение',
    );

    private static $instance;
    private $devices_table;
    private $telemetry_table;
    private $commands_table;
    private $geofences_table;
    private $events_table;

    public static function instance() {
        if (!self::$instance) {
            self::$instance = new self();
        }
        return self::$instance;
    }

    private function __construct() {
        global $wpdb;
        $this->devices_table = $wpdb->prefix . 'family_guard_devices';
        $this->telemetry_table = $wpdb->prefix . 'family_guard_telemetry';
        $this->commands_table = $wpdb->prefix . 'family_guard_commands';
        $this->geofences_table = $wpdb->prefix . 'family_guard_geofences';
        $this->events_table = $wpdb->prefix . 'family_guard_events';

        add_action('rest_api_init', array($this, 'register_routes'));
        add_action('admin_menu', array($this, 'register_admin_page'));
        add_action('admin_init', array($this, 'maybe_upgrade'));
        add_action('admin_post_fg_generate_pairing', array($this, 'admin_generate_pairing'));
        add_action('admin_post_fg_camera_request', array($this, 'admin_camera_request'));
        add_action('admin_post_fg_block_apps', array($this, 'admin_block_apps'));
        add_action('admin_post_fg_snapshot', array($this, 'admin_serve_snapshot'));
        add_action('admin_post_fg_remove_device', array($this, 'admin_remove_device'));
        add_action('admin_post_fg_lost_on', array($this, 'admin_lost_on'));
        add_action('admin_post_fg_lost_off', array($this, 'admin_lost_off'));
        add_action('admin_post_fg_ring', array($this, 'admin_ring'));
        add_action('admin_post_fg_save_geofence', array($this, 'admin_save_geofence'));
        add_action('admin_post_fg_delete_geofence', array($this, 'admin_delete_geofence'));
        add_action('admin_post_fg_save_settings', array($this, 'admin_save_settings'));
        add_action('admin_post_fg_rename_device', array($this, 'admin_rename_device'));
        add_action('admin_post_fg_export', array($this, 'admin_export'));
    }

    // Настройки панели с безопасными значениями по умолчанию.
    private function settings() {
        $defaults = array(
            'low_battery' => self::LOW_BATTERY,
            'email_enabled' => 0,
            'email_to' => get_option('admin_email'),
            'theme' => 'auto',
        );
        $saved = get_option(self::OPTION_SETTINGS, array());
        return array_merge($defaults, is_array($saved) ? $saved : array());
    }

    public function admin_lost_on() {
        $this->require_admin_action('fg_lost_on');
        $device_id = isset($_POST['device_id']) ? absint($_POST['device_id']) : 0;
        $message = isset($_POST['lost_message']) ? mb_substr(sanitize_text_field(wp_unslash($_POST['lost_message'])), 0, 200) : '';
        $phone = isset($_POST['lost_phone']) ? preg_replace('/[^0-9+]/', '', (string) wp_unslash($_POST['lost_phone'])) : '';
        $this->insert_command($device_id, 'lost_mode', array('active' => true, 'message' => $message, 'phone' => mb_substr($phone, 0, 20)));
        $this->redirect_admin('lost_on', $device_id);
    }

    public function admin_lost_off() {
        $this->require_admin_action('fg_lost_off');
        $device_id = isset($_POST['device_id']) ? absint($_POST['device_id']) : 0;
        $this->insert_command($device_id, 'lost_mode', array('active' => false));
        $this->redirect_admin('lost_off', $device_id);
    }

    public function admin_ring() {
        $this->require_admin_action('fg_ring');
        $device_id = isset($_POST['device_id']) ? absint($_POST['device_id']) : 0;
        $this->insert_command($device_id, 'ring', array());
        $this->redirect_admin('ring_sent', $device_id);
    }

    // Текущее состояние режима пропажи по последней отправленной команде.
    private function lost_state($device_id) {
        global $wpdb;
        $json = $wpdb->get_var($wpdb->prepare(
            "SELECT payload_json FROM {$this->commands_table} WHERE device_id = %d AND command_type = 'lost_mode' ORDER BY id DESC LIMIT 1",
            $device_id
        ));
        $payload = json_decode((string) $json, true);
        return is_array($payload) ? !empty($payload['active']) : false;
    }

    public function maybe_upgrade() {
        if ((int) get_option(self::OPTION_DB, 1) < self::DB_VERSION) {
            self::activate();
        }
    }

    private static function private_dir() {
        $uploads = wp_upload_dir();
        return trailingslashit($uploads['basedir']) . self::PRIVATE_DIR;
    }

    private static function ensure_private_dir() {
        $dir = self::private_dir();
        if (!is_dir($dir)) wp_mkdir_p($dir);
        if (!file_exists($dir . '/.htaccess')) {
            file_put_contents($dir . '/.htaccess', "Require all denied\nDeny from all\n");
        }
        if (!file_exists($dir . '/index.php')) {
            file_put_contents($dir . '/index.php', "<?php // Silence is golden.\n");
        }
        return $dir;
    }

    public static function activate() {
        global $wpdb;
        $charset = $wpdb->get_charset_collate();
        require_once ABSPATH . 'wp-admin/includes/upgrade.php';

        $devices = $wpdb->prefix . 'family_guard_devices';
        $telemetry = $wpdb->prefix . 'family_guard_telemetry';
        $commands = $wpdb->prefix . 'family_guard_commands';
        $geofences = $wpdb->prefix . 'family_guard_geofences';
        $events = $wpdb->prefix . 'family_guard_events';

        dbDelta("CREATE TABLE {$devices} (
            id bigint(20) unsigned NOT NULL AUTO_INCREMENT,
            device_uuid varchar(64) NOT NULL,
            name varchar(190) NOT NULL,
            token_hash char(64) NOT NULL,
            platform varchar(30) NOT NULL DEFAULT 'android',
            model varchar(190) NOT NULL DEFAULT '',
            android_version varchar(50) NOT NULL DEFAULT '',
            app_version varchar(30) NOT NULL DEFAULT '',
            created_at datetime NOT NULL,
            last_seen datetime NULL,
            PRIMARY KEY (id),
            UNIQUE KEY device_uuid (device_uuid)
        ) {$charset};");

        dbDelta("CREATE TABLE {$telemetry} (
            id bigint(20) unsigned NOT NULL AUTO_INCREMENT,
            device_id bigint(20) unsigned NOT NULL,
            battery smallint NOT NULL DEFAULT -1,
            latitude decimal(10,7) NULL,
            longitude decimal(10,7) NULL,
            apps_json longtext NULL,
            perms_json longtext NULL,
            screen_today_ms bigint(20) NULL,
            daily_limit_minutes int NULL,
            interval_minutes int NULL,
            device_time bigint(20) NULL,
            created_at datetime NOT NULL,
            PRIMARY KEY (id),
            KEY device_created (device_id, created_at)
        ) {$charset};");

        dbDelta("CREATE TABLE {$commands} (
            id bigint(20) unsigned NOT NULL AUTO_INCREMENT,
            device_id bigint(20) unsigned NOT NULL,
            command_type varchar(80) NOT NULL,
            payload_json longtext NULL,
            status varchar(30) NOT NULL DEFAULT 'pending',
            result_json longtext NULL,
            created_at datetime NOT NULL,
            delivered_at datetime NULL,
            completed_at datetime NULL,
            PRIMARY KEY (id),
            KEY device_status (device_id, status)
        ) {$charset};");

        dbDelta("CREATE TABLE {$geofences} (
            id bigint(20) unsigned NOT NULL AUTO_INCREMENT,
            device_id bigint(20) unsigned NOT NULL,
            name varchar(190) NOT NULL,
            latitude decimal(10,7) NOT NULL,
            longitude decimal(10,7) NOT NULL,
            radius_m int NOT NULL DEFAULT 200,
            notify_enter tinyint(1) NOT NULL DEFAULT 1,
            notify_exit tinyint(1) NOT NULL DEFAULT 1,
            last_inside tinyint(1) NOT NULL DEFAULT -1,
            created_at datetime NOT NULL,
            PRIMARY KEY (id),
            KEY device_id (device_id)
        ) {$charset};");

        dbDelta("CREATE TABLE {$events} (
            id bigint(20) unsigned NOT NULL AUTO_INCREMENT,
            device_id bigint(20) unsigned NOT NULL,
            event_type varchar(40) NOT NULL,
            message varchar(255) NOT NULL DEFAULT '',
            data_json longtext NULL,
            created_at datetime NOT NULL,
            PRIMARY KEY (id),
            KEY device_created (device_id, created_at)
        ) {$charset};");

        self::ensure_private_dir();
        update_option(self::OPTION_DB, self::DB_VERSION, false);
    }

    public function register_routes() {
        register_rest_route(self::REST_NAMESPACE, '/pair', array(
            'methods' => 'POST',
            'callback' => array($this, 'rest_pair'),
            'permission_callback' => '__return_true',
        ));
        register_rest_route(self::REST_NAMESPACE, '/telemetry', array(
            'methods' => 'POST',
            'callback' => array($this, 'rest_telemetry'),
            'permission_callback' => '__return_true',
        ));
        register_rest_route(self::REST_NAMESPACE, '/commands', array(
            'methods' => 'GET',
            'callback' => array($this, 'rest_commands'),
            'permission_callback' => '__return_true',
        ));
        register_rest_route(self::REST_NAMESPACE, '/commands/(?P<id>\d+)/result', array(
            'methods' => 'POST',
            'callback' => array($this, 'rest_command_result'),
            'permission_callback' => '__return_true',
        ));
        register_rest_route(self::REST_NAMESPACE, '/snapshot', array(
            'methods' => 'POST',
            'callback' => array($this, 'rest_snapshot'),
            'permission_callback' => '__return_true',
        ));
        register_rest_route(self::REST_NAMESPACE, '/event', array(
            'methods' => 'POST',
            'callback' => array($this, 'rest_event'),
            'permission_callback' => '__return_true',
        ));
    }

    // Разовое событие с телефона (например, нажатие SOS ребёнком).
    public function rest_event(WP_REST_Request $request) {
        $device = $this->authenticate_device($request);
        if (is_wp_error($device)) return $device;
        $type = sanitize_key((string) $request->get_param('type'));
        if (!array_key_exists($type, self::EVENT_LABELS)) {
            return new WP_Error('fg_bad_event', 'Неизвестный тип события.', array('status' => 400));
        }
        $lat = $request->has_param('latitude') ? (float) $request->get_param('latitude') : null;
        $lon = $request->has_param('longitude') ? (float) $request->get_param('longitude') : null;
        $data = array();
        if ($lat !== null && $lat >= -90 && $lat <= 90 && $lon !== null && $lon >= -180 && $lon <= 180) {
            $data = array('latitude' => $lat, 'longitude' => $lon);
        }
        $message = $type === 'sos' ? 'Ребёнок нажал кнопку SOS.' : self::EVENT_LABELS[$type];
        $this->log_event($device->id, $device->name, $type, $message, $data);
        return rest_ensure_response(array('ok' => true));
    }

    public function rest_pair(WP_REST_Request $request) {
        global $wpdb;
        $ip = isset($_SERVER['REMOTE_ADDR']) ? sanitize_text_field(wp_unslash($_SERVER['REMOTE_ADDR'])) : 'unknown';
        $rate_key = 'fg_pair_' . md5($ip);
        $attempts = (int) get_transient($rate_key);
        if ($attempts >= 10) {
            return new WP_Error('fg_rate_limit', 'Слишком много попыток. Повторите позже.', array('status' => 429));
        }
        set_transient($rate_key, $attempts + 1, 10 * MINUTE_IN_SECONDS);

        $saved = get_option(self::OPTION_PAIRING, array());
        $code = preg_replace('/\D+/', '', (string) $request->get_param('pairing_code'));
        if (empty($saved['hash']) || empty($saved['expires']) || time() > (int) $saved['expires'] ||
            !hash_equals($saved['hash'], hash('sha256', $code))) {
            return new WP_Error('fg_bad_pairing', 'Неверный или просроченный код подключения.', array('status' => 403));
        }

        $name = sanitize_text_field((string) $request->get_param('device_name'));
        $name = $name ? mb_substr($name, 0, 190) : 'Android';
        $token = bin2hex(random_bytes(32));
        $uuid = wp_generate_uuid4();
        $inserted = $wpdb->insert($this->devices_table, array(
            'device_uuid' => $uuid,
            'name' => $name,
            'token_hash' => hash('sha256', $token),
            'platform' => 'android',
            'model' => mb_substr(sanitize_text_field((string) $request->get_param('model')), 0, 190),
            'android_version' => mb_substr(sanitize_text_field((string) $request->get_param('android_version')), 0, 50),
            'app_version' => mb_substr(sanitize_text_field((string) $request->get_param('app_version')), 0, 30),
            'created_at' => current_time('mysql', true),
            'last_seen' => current_time('mysql', true),
        ));
        if (!$inserted) {
            return new WP_Error('fg_database', 'Не удалось сохранить устройство.', array('status' => 500));
        }

        delete_option(self::OPTION_PAIRING);
        delete_transient($rate_key);
        return rest_ensure_response(array('device_id' => $uuid, 'token' => $token));
    }

    public function rest_telemetry(WP_REST_Request $request) {
        global $wpdb;
        $device = $this->authenticate_device($request);
        if (is_wp_error($device)) {
            return $device;
        }

        $battery = (int) $request->get_param('battery');
        $battery = max(-1, min(100, $battery));
        $latitude = $request->has_param('latitude') ? (float) $request->get_param('latitude') : null;
        $longitude = $request->has_param('longitude') ? (float) $request->get_param('longitude') : null;
        if ($latitude !== null && ($latitude < -90 || $latitude > 90)) $latitude = null;
        if ($longitude !== null && ($longitude < -180 || $longitude > 180)) $longitude = null;

        $apps = $request->get_param('apps');
        $clean_apps = array();
        if (is_array($apps)) {
            foreach (array_slice($apps, 0, 100) as $app) {
                if (!is_array($app)) continue;
                $package = preg_replace('/[^a-zA-Z0-9._-]/', '', (string) ($app['package'] ?? ''));
                if (!$package) continue;
                $clean_apps[] = array(
                    'package' => mb_substr($package, 0, 190),
                    'label' => mb_substr(sanitize_text_field((string) ($app['label'] ?? $package)), 0, 190),
                    'foreground_ms' => max(0, (int) ($app['foreground_ms'] ?? 0)),
                );
            }
        }

        $perms = $request->get_param('permissions');
        $clean_perms = array();
        if (is_array($perms)) {
            foreach (array_keys(self::PERMISSION_LABELS) as $key) {
                if (array_key_exists($key, $perms)) $clean_perms[$key] = (bool) $perms[$key];
            }
        }

        // Предыдущий отчёт нужен, чтобы ловить переходы (вошёл/вышел из зоны, сел заряд, перебор лимита).
        $prev = $wpdb->get_row($wpdb->prepare(
            "SELECT battery, latitude, longitude, screen_today_ms, daily_limit_minutes FROM {$this->telemetry_table} WHERE device_id = %d ORDER BY id DESC LIMIT 1",
            $device->id
        ));
        $screen_today = $request->has_param('screen_today_ms') ? max(0, (int) $request->get_param('screen_today_ms')) : null;
        $daily_limit = $request->has_param('daily_limit_minutes') ? max(0, (int) $request->get_param('daily_limit_minutes')) : null;

        $wpdb->insert($this->telemetry_table, array(
            'device_id' => $device->id,
            'battery' => $battery,
            'latitude' => $latitude,
            'longitude' => $longitude,
            'apps_json' => wp_json_encode($clean_apps),
            'perms_json' => wp_json_encode($clean_perms),
            'screen_today_ms' => $screen_today,
            'daily_limit_minutes' => $daily_limit,
            'interval_minutes' => $request->has_param('interval_minutes') ? max(1, (int) $request->get_param('interval_minutes')) : null,
            'device_time' => (int) $request->get_param('device_time'),
            'created_at' => current_time('mysql', true),
        ));

        $update = array('last_seen' => current_time('mysql', true));
        $app_version = sanitize_text_field((string) $request->get_param('app_version'));
        if ($app_version) $update['app_version'] = mb_substr($app_version, 0, 30);
        $wpdb->update($this->devices_table, $update, array('id' => $device->id));

        $this->process_telemetry_events($device, $prev, $battery, $latitude, $longitude, $screen_today, $daily_limit);

        $wpdb->query($wpdb->prepare(
            "DELETE FROM {$this->telemetry_table} WHERE device_id = %d AND created_at < %s",
            $device->id, gmdate('Y-m-d H:i:s', time() - 30 * DAY_IN_SECONDS)
        ));
        return rest_ensure_response(array('ok' => true));
    }

    public function rest_commands(WP_REST_Request $request) {
        global $wpdb;
        $device = $this->authenticate_device($request);
        if (is_wp_error($device)) return $device;

        $rows = $wpdb->get_results($wpdb->prepare(
            "SELECT id, command_type, payload_json FROM {$this->commands_table}
             WHERE device_id = %d AND status IN ('pending','delivered') ORDER BY id ASC LIMIT 20",
            $device->id
        ));
        $ids = array();
        $commands = array();
        foreach ($rows as $row) {
            $ids[] = (int) $row->id;
            $commands[] = array(
                'id' => (int) $row->id,
                'type' => $row->command_type,
                'payload' => json_decode($row->payload_json ?: '{}', true),
            );
        }
        if ($ids) {
            $placeholders = implode(',', array_fill(0, count($ids), '%d'));
            $args = array_merge(array(current_time('mysql', true)), $ids);
            $wpdb->query($wpdb->prepare(
                "UPDATE {$this->commands_table} SET status = 'delivered', delivered_at = %s
                 WHERE status = 'pending' AND id IN ({$placeholders})",
                $args
            ));
        }
        $wpdb->update($this->devices_table, array('last_seen' => current_time('mysql', true)), array('id' => $device->id));
        return rest_ensure_response(array('commands' => $commands));
    }

    public function rest_command_result(WP_REST_Request $request) {
        global $wpdb;
        $device = $this->authenticate_device($request);
        if (is_wp_error($device)) return $device;
        $command_id = (int) $request['id'];
        $command = $wpdb->get_row($wpdb->prepare(
            "SELECT * FROM {$this->commands_table} WHERE id = %d AND device_id = %d",
            $command_id, $device->id
        ));
        if (!$command) return new WP_Error('fg_not_found', 'Команда не найдена.', array('status' => 404));

        $status = sanitize_key((string) $request->get_param('status'));
        if (!in_array($status, array('completed', 'failed', 'declined'), true)) {
            return new WP_Error('fg_bad_status', 'Недопустимый статус.', array('status' => 400));
        }
        $result = json_decode($command->result_json ?: '{}', true);
        if (!is_array($result)) $result = array();
        $result['message'] = mb_substr(sanitize_text_field((string) $request->get_param('message')), 0, 500);
        $wpdb->update($this->commands_table, array(
            'status' => $status,
            'result_json' => wp_json_encode($result),
            'completed_at' => current_time('mysql', true),
        ), array('id' => $command_id));
        return rest_ensure_response(array('ok' => true));
    }

    public function rest_snapshot(WP_REST_Request $request) {
        global $wpdb;
        $device = $this->authenticate_device($request);
        if (is_wp_error($device)) return $device;
        $command_id = (int) $request->get_param('command_id');
        $command = $wpdb->get_row($wpdb->prepare(
            "SELECT * FROM {$this->commands_table} WHERE id = %d AND device_id = %d AND command_type = 'camera_snapshot'",
            $command_id, $device->id
        ));
        if (!$command) return new WP_Error('fg_not_found', 'Запрос снимка не найден.', array('status' => 404));

        $file = isset($_FILES['snapshot']) ? $_FILES['snapshot'] : null;
        if (!$file || !empty($file['error']) || (int) $file['size'] <= 0 || (int) $file['size'] > 5 * MB_IN_BYTES ||
            !is_uploaded_file($file['tmp_name'])) {
            return new WP_Error('fg_bad_file', 'Нужен JPEG-снимок размером до 5 МБ.', array('status' => 400));
        }
        $info = @getimagesize($file['tmp_name']);
        if (!$info || (isset($info['mime']) ? $info['mime'] : '') !== 'image/jpeg') {
            return new WP_Error('fg_bad_file', 'Файл не является JPEG-снимком.', array('status' => 400));
        }

        $dir = self::ensure_private_dir();
        $name = sprintf('%d-%d-%s.jpg', (int) $device->id, $command_id, bin2hex(random_bytes(12)));
        if (!move_uploaded_file($file['tmp_name'], $dir . '/' . $name)) {
            return new WP_Error('fg_storage', 'Не удалось сохранить снимок.', array('status' => 500));
        }

        $result = json_decode($command->result_json ?: '{}', true);
        if (!is_array($result)) $result = array();
        $result['snapshot_file'] = $name;
        $result['snapshot_at'] = current_time('mysql', true);
        $wpdb->update($this->commands_table, array('result_json' => wp_json_encode($result)), array('id' => $command_id));
        return rest_ensure_response(array('ok' => true));
    }

    public function admin_serve_snapshot() {
        if (!current_user_can('manage_options')) wp_die('Недостаточно прав.', '', array('response' => 403));
        $command_id = isset($_GET['command']) ? absint($_GET['command']) : 0;
        check_admin_referer('fg_snapshot_' . $command_id);
        global $wpdb;
        $json = $wpdb->get_var($wpdb->prepare(
            "SELECT result_json FROM {$this->commands_table} WHERE id = %d AND command_type = 'camera_snapshot'", $command_id
        ));
        $result = json_decode((string) $json, true);
        $name = is_array($result) ? basename((string) (isset($result['snapshot_file']) ? $result['snapshot_file'] : '')) : '';
        $path = self::private_dir() . '/' . $name;
        if (!$name || !is_file($path)) wp_die('Снимок не найден.', '', array('response' => 404));
        nocache_headers();
        header('Content-Type: image/jpeg');
        header('Content-Length: ' . filesize($path));
        header('X-Content-Type-Options: nosniff');
        readfile($path);
        exit;
    }

    private function snapshot_url($command_id) {
        return wp_nonce_url(
            add_query_arg(array('action' => 'fg_snapshot', 'command' => (int) $command_id), admin_url('admin-post.php')),
            'fg_snapshot_' . (int) $command_id
        );
    }

    private function authenticate_device(WP_REST_Request $request) {
        global $wpdb;
        $uuid = sanitize_text_field((string) $request->get_header('x-family-guard-device'));
        $authorization = (string) $request->get_header('authorization');
        if (!$authorization && isset($_SERVER['HTTP_AUTHORIZATION'])) {
            $authorization = sanitize_text_field(wp_unslash($_SERVER['HTTP_AUTHORIZATION']));
        }
        if (!$uuid || !preg_match('/^Bearer\s+([a-f0-9]{64})$/i', $authorization, $match)) {
            return new WP_Error('fg_unauthorized', 'Требуется авторизация устройства.', array('status' => 401));
        }
        $device = $wpdb->get_row($wpdb->prepare(
            "SELECT * FROM {$this->devices_table} WHERE device_uuid = %s", $uuid
        ));
        if (!$device || !hash_equals($device->token_hash, hash('sha256', $match[1]))) {
            return new WP_Error('fg_unauthorized', 'Неверный токен устройства.', array('status' => 401));
        }
        return $device;
    }

    public function register_admin_page() {
        $svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20"><path d="M10 1 2.5 3.8v5.3c0 4.2 3.1 7.4 7.5 9.1 4.4-1.7 7.5-4.9 7.5-9.1V3.8L10 1Zm-1 12.2-3-3 1.2-1.2L9 10.8l3.8-3.8L14 8.2l-5 5Z"/></svg>';
        add_menu_page(
            'Family Guard', 'Family Guard', 'manage_options', 'family-guard',
            array($this, 'render_admin_page'),
            'data:image/svg+xml;base64,' . base64_encode($svg), 58
        );
    }

    public function admin_generate_pairing() {
        $this->require_admin_action('fg_generate_pairing');
        $code = (string) random_int(100000, 999999);
        update_option(self::OPTION_PAIRING, array(
            'hash' => hash('sha256', $code),
            'display' => $code,
            'expires' => time() + 10 * MINUTE_IN_SECONDS,
        ), false);
        $this->redirect_admin('pairing_created');
    }

    public function admin_camera_request() {
        $this->require_admin_action('fg_camera_request');
        $device_id = isset($_POST['device_id']) ? absint($_POST['device_id']) : 0;
        $this->insert_command($device_id, 'camera_snapshot', array());
        $this->redirect_admin('camera_requested', $device_id);
    }

    public function admin_block_apps() {
        $this->require_admin_action('fg_block_apps');
        $device_id = isset($_POST['device_id']) ? absint($_POST['device_id']) : 0;
        $raw = isset($_POST['packages']) ? sanitize_textarea_field(wp_unslash($_POST['packages'])) : '';
        $packages = preg_split('/[\s,;]+/', $raw, -1, PREG_SPLIT_NO_EMPTY);
        $packages = array_values(array_unique(array_filter(array_map(function ($package) {
            return preg_match('/^[a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+$/', $package) ? $package : '';
        }, $packages))));
        $this->insert_command($device_id, 'set_blocked_apps', array('packages' => array_slice($packages, 0, 100)));
        $this->redirect_admin('apps_updated', $device_id);
    }

    private function require_admin_action($action) {
        if (!current_user_can('manage_options')) wp_die('Недостаточно прав.');
        check_admin_referer($action);
    }

    private function insert_command($device_id, $type, $payload) {
        global $wpdb;
        $exists = $wpdb->get_var($wpdb->prepare("SELECT id FROM {$this->devices_table} WHERE id = %d", $device_id));
        if (!$exists) wp_die('Устройство не найдено.');
        $wpdb->insert($this->commands_table, array(
            'device_id' => $device_id,
            'command_type' => $type,
            'payload_json' => wp_json_encode($payload),
            'status' => 'pending',
            'created_at' => current_time('mysql', true),
        ));
    }

    private function redirect_admin($message, $device_id = 0, $tab = '') {
        $args = array('page' => 'family-guard', 'fg_message' => $message);
        if ($device_id) $args['device'] = (int) $device_id;
        if ($tab) $args['tab'] = sanitize_key($tab);
        wp_safe_redirect(add_query_arg($args, admin_url('admin.php')));
        exit;
    }

    public function admin_remove_device() {
        $this->require_admin_action('fg_remove_device');
        global $wpdb;
        $device_id = isset($_POST['device_id']) ? absint($_POST['device_id']) : 0;
        $files = $wpdb->get_col($wpdb->prepare(
            "SELECT result_json FROM {$this->commands_table} WHERE device_id = %d AND command_type = 'camera_snapshot'", $device_id
        ));
        foreach ($files as $json) {
            $result = json_decode((string) $json, true);
            $name = is_array($result) ? basename((string) (isset($result['snapshot_file']) ? $result['snapshot_file'] : '')) : '';
            if ($name && file_exists(self::private_dir() . '/' . $name)) wp_delete_file(self::private_dir() . '/' . $name);
        }
        $wpdb->delete($this->telemetry_table, array('device_id' => $device_id));
        $wpdb->delete($this->commands_table, array('device_id' => $device_id));
        $wpdb->delete($this->devices_table, array('id' => $device_id));
        $this->redirect_admin('device_removed');
    }

    // Расстояние между точками в метрах (формула гаверсинуса).
    private function distance_m($lat1, $lon1, $lat2, $lon2) {
        $r = 6371000.0;
        $dlat = deg2rad($lat2 - $lat1);
        $dlon = deg2rad($lon2 - $lon1);
        $a = sin($dlat / 2) ** 2 + cos(deg2rad($lat1)) * cos(deg2rad($lat2)) * sin($dlon / 2) ** 2;
        return 2 * $r * atan2(sqrt($a), sqrt(1 - $a));
    }

    // Проверяет переходы по заряду, лимиту и геозонам и пишет события.
    private function process_telemetry_events($device, $prev, $battery, $lat, $lon, $screen_ms, $limit_min) {
        global $wpdb;
        $settings = $this->settings();
        $low = max(1, min(100, (int) $settings['low_battery']));

        if ($battery >= 0 && $battery < $low) {
            $prev_ok = !$prev || (int) $prev->battery < 0 || (int) $prev->battery >= $low;
            if ($prev_ok) {
                $this->log_event($device->id, $device->name, 'low_battery', 'Низкий заряд батареи: ' . $battery . '%.', array('battery' => $battery));
            }
        }

        if ($limit_min !== null && $limit_min > 0 && $screen_ms !== null) {
            $limit_ms = $limit_min * 60 * 1000;
            $prev_under = !$prev || $prev->screen_today_ms === null || (int) $prev->screen_today_ms <= $limit_ms;
            if ($screen_ms > $limit_ms && $prev_under) {
                $this->log_event($device->id, $device->name, 'limit_exceeded', 'Превышен дневной лимит экрана (' . $limit_min . ' мин).', array());
            }
        }

        if ($lat === null || $lon === null) return;
        $fences = $wpdb->get_results($wpdb->prepare(
            "SELECT * FROM {$this->geofences_table} WHERE device_id = %d", $device->id
        ));
        foreach ($fences as $fence) {
            $inside = $this->distance_m((float) $lat, (float) $lon, (float) $fence->latitude, (float) $fence->longitude) <= (float) $fence->radius_m;
            $was = (int) $fence->last_inside; // -1 неизвестно, 0 снаружи, 1 внутри
            $wpdb->update($this->geofences_table, array('last_inside' => $inside ? 1 : 0), array('id' => $fence->id));
            if ($was === -1 || ($inside ? 1 : 0) === $was) continue;
            if ($inside && $fence->notify_enter) {
                $this->log_event($device->id, $device->name, 'geofence_enter', $device->name . ' пришёл в зону «' . $fence->name . '».', array('geofence' => $fence->name, 'latitude' => (float) $lat, 'longitude' => (float) $lon));
            } elseif (!$inside && $fence->notify_exit) {
                $this->log_event($device->id, $device->name, 'geofence_exit', $device->name . ' вышел из зоны «' . $fence->name . '».', array('geofence' => $fence->name, 'latitude' => (float) $lat, 'longitude' => (float) $lon));
            }
        }
    }

    // Записывает событие в ленту и, если включено, шлёт письмо родителю.
    private function log_event($device_id, $device_name, $type, $message, $data = array()) {
        global $wpdb;
        $wpdb->insert($this->events_table, array(
            'device_id' => (int) $device_id,
            'event_type' => $type,
            'message' => mb_substr($message, 0, 255),
            'data_json' => wp_json_encode($data),
            'created_at' => current_time('mysql', true),
        ));
        $wpdb->query($wpdb->prepare(
            "DELETE FROM {$this->events_table} WHERE device_id = %d AND created_at < %s",
            (int) $device_id, gmdate('Y-m-d H:i:s', time() - 60 * DAY_IN_SECONDS)
        ));

        $settings = $this->settings();
        if (empty($settings['email_enabled'])) return;
        $to = sanitize_email((string) $settings['email_to']);
        if (!is_email($to)) return;
        $subject = 'Family Guard: ' . (isset(self::EVENT_LABELS[$type]) ? self::EVENT_LABELS[$type] : 'уведомление') . ' — ' . $device_name;
        $body = $message . "\n\n";
        if (!empty($data['latitude']) && !empty($data['longitude'])) {
            $body .= 'Местоположение: https://www.openstreetmap.org/?mlat=' . $data['latitude'] . '&mlon=' . $data['longitude'] . "#map=16/" . $data['latitude'] . '/' . $data['longitude'] . "\n";
        }
        $body .= "\nОткрыть панель: " . admin_url('admin.php?page=family-guard');
        wp_mail($to, $subject, $body);
    }

    public function admin_save_geofence() {
        $this->require_admin_action('fg_save_geofence');
        global $wpdb;
        $device_id = isset($_POST['device_id']) ? absint($_POST['device_id']) : 0;
        if (!$wpdb->get_var($wpdb->prepare("SELECT id FROM {$this->devices_table} WHERE id = %d", $device_id))) {
            wp_die('Устройство не найдено.');
        }
        $name = isset($_POST['name']) ? mb_substr(sanitize_text_field(wp_unslash($_POST['name'])), 0, 190) : '';
        $lat = isset($_POST['latitude']) ? (float) $_POST['latitude'] : 200;
        $lon = isset($_POST['longitude']) ? (float) $_POST['longitude'] : 200;
        $radius = isset($_POST['radius_m']) ? max(50, min(10000, absint($_POST['radius_m']))) : 200;
        if (!$name || $lat < -90 || $lat > 90 || $lon < -180 || $lon > 180) {
            $this->redirect_admin('geofence_bad', $device_id, 'geofences');
        }
        $wpdb->insert($this->geofences_table, array(
            'device_id' => $device_id,
            'name' => $name,
            'latitude' => $lat,
            'longitude' => $lon,
            'radius_m' => $radius,
            'notify_enter' => isset($_POST['notify_enter']) ? 1 : 0,
            'notify_exit' => isset($_POST['notify_exit']) ? 1 : 0,
            'last_inside' => -1,
            'created_at' => current_time('mysql', true),
        ));
        $this->redirect_admin('geofence_saved', $device_id, 'geofences');
    }

    public function admin_delete_geofence() {
        $this->require_admin_action('fg_delete_geofence');
        global $wpdb;
        $id = isset($_POST['geofence_id']) ? absint($_POST['geofence_id']) : 0;
        $wpdb->delete($this->geofences_table, array('id' => $id));
        $this->redirect_admin('geofence_deleted', 0, 'geofences');
    }

    public function admin_save_settings() {
        $this->require_admin_action('fg_save_settings');
        $settings = array(
            'low_battery' => isset($_POST['low_battery']) ? max(1, min(100, absint($_POST['low_battery']))) : self::LOW_BATTERY,
            'email_enabled' => isset($_POST['email_enabled']) ? 1 : 0,
            'email_to' => isset($_POST['email_to']) ? sanitize_email(wp_unslash($_POST['email_to'])) : get_option('admin_email'),
            'theme' => isset($_POST['theme']) && in_array($_POST['theme'], array('auto', 'light', 'dark'), true) ? sanitize_key($_POST['theme']) : 'auto',
        );
        update_option(self::OPTION_SETTINGS, $settings, false);
        $this->redirect_admin('settings_saved', 0, 'settings');
    }

    public function admin_rename_device() {
        $this->require_admin_action('fg_rename_device');
        global $wpdb;
        $device_id = isset($_POST['device_id']) ? absint($_POST['device_id']) : 0;
        $name = isset($_POST['name']) ? mb_substr(sanitize_text_field(wp_unslash($_POST['name'])), 0, 190) : '';
        if ($device_id && $name) $wpdb->update($this->devices_table, array('name' => $name), array('id' => $device_id));
        $this->redirect_admin('device_renamed', $device_id, 'device');
    }

    // Выгрузка истории телефона в CSV.
    public function admin_export() {
        if (!current_user_can('manage_options')) wp_die('Недостаточно прав.', '', array('response' => 403));
        $device_id = isset($_GET['device']) ? absint($_GET['device']) : 0;
        check_admin_referer('fg_export_' . $device_id);
        global $wpdb;
        $rows = $wpdb->get_results($wpdb->prepare(
            "SELECT created_at, battery, latitude, longitude, screen_today_ms FROM {$this->telemetry_table} WHERE device_id = %d ORDER BY id ASC", $device_id
        ));
        nocache_headers();
        header('Content-Type: text/csv; charset=utf-8');
        header('Content-Disposition: attachment; filename="family-guard-' . $device_id . '.csv"');
        $out = fopen('php://output', 'w');
        fputcsv($out, array('Время (UTC)', 'Заряд %', 'Широта', 'Долгота', 'Экран сегодня, мин'));
        foreach ($rows as $row) {
            fputcsv($out, array(
                $row->created_at,
                $row->battery,
                $row->latitude,
                $row->longitude,
                $row->screen_today_ms !== null ? round(((int) $row->screen_today_ms) / 60000) : '',
            ));
        }
        fclose($out);
        exit;
    }

    // Последние точки маршрута для карты истории.
    private function history_points($device_id, $limit = 50) {
        global $wpdb;
        $rows = $wpdb->get_results($wpdb->prepare(
            "SELECT latitude, longitude, created_at FROM {$this->telemetry_table}
             WHERE device_id = %d AND latitude IS NOT NULL AND longitude IS NOT NULL
             ORDER BY id DESC LIMIT %d", $device_id, $limit
        ));
        $points = array();
        foreach (array_reverse($rows) as $row) {
            $points[] = array('lat' => (float) $row->latitude, 'lon' => (float) $row->longitude, 'at' => get_date_from_gmt($row->created_at, 'd.m H:i'));
        }
        return $points;
    }

    private function device_warnings($device, $latest) {
        $warnings = array();
        $interval = ($latest && $latest->interval_minutes) ? (int) $latest->interval_minutes : 5;
        $offline_after = max(self::OFFLINE_MINUTES, $interval * 3) * MINUTE_IN_SECONDS;
        $last_seen = $device->last_seen ? strtotime($device->last_seen . ' UTC') : 0;
        if (!$last_seen || time() - $last_seen > $offline_after) {
            $warnings[] = 'Телефон давно не выходил на связь. Возможно, защита остановлена, нет интернета или телефон выключен.';
        }
        if (!$latest) return $warnings;
        $perms = json_decode((string) $latest->perms_json, true);
        if (is_array($perms)) {
            foreach (self::PERMISSION_LABELS as $key => $label) {
                if (array_key_exists($key, $perms) && !$perms[$key]) $warnings[] = 'Отключено: ' . $label . '.';
            }
        }
        $low = max(1, min(100, (int) $this->settings()['low_battery']));
        if ((int) $latest->battery >= 0 && (int) $latest->battery < $low) {
            $warnings[] = 'Низкий заряд батареи: ' . (int) $latest->battery . '%.';
        }
        if ($latest->latitude === null) {
            $warnings[] = 'Нет данных о местоположении в последнем отчёте.';
        }
        $limit = (int) $latest->daily_limit_minutes;
        if ($limit > 0 && (int) $latest->screen_today_ms > $limit * MINUTE_IN_SECONDS * 1000) {
            $warnings[] = 'Превышен дневной лимит экрана (' . $limit . ' мин).';
        }
        return $warnings;
    }

    private function format_minutes($ms) {
        $minutes = (int) round(((int) $ms) / 60000);
        if ($minutes < 60) return $minutes . ' мин';
        return intdiv($minutes, 60) . ' ч ' . ($minutes % 60) . ' мин';
    }

    private function map_link($lat, $lon, $label = 'карта') {
        $url = 'https://www.openstreetmap.org/?mlat=' . rawurlencode($lat) . '&mlon=' . rawurlencode($lon) .
            '#map=16/' . rawurlencode($lat) . '/' . rawurlencode($lon);
        return '<a target="_blank" rel="noopener" href="' . esc_url($url) . '">' . esc_html($label) . '</a>';
    }

    public function render_admin_page() {
        if (!current_user_can('manage_options')) return;
        global $wpdb;
        $devices = $wpdb->get_results("SELECT * FROM {$this->devices_table} ORDER BY id DESC");
        $settings = $this->settings();
        $tab = isset($_GET['tab']) ? sanitize_key(wp_unslash($_GET['tab'])) : 'overview';
        $valid = array('overview', 'device', 'geofences', 'connect', 'settings');
        if (!in_array($tab, $valid, true)) $tab = 'overview';
        $message = isset($_GET['fg_message']) ? sanitize_key(wp_unslash($_GET['fg_message'])) : '';
        $notices = array(
            'pairing_created' => 'Одноразовый код создан.',
            'camera_requested' => 'Запрос отправлен. На телефоне появится заметное уведомление.',
            'apps_updated' => 'Новый список ограничений отправлен. В версии lite блокировка недоступна.',
            'device_removed' => 'Телефон и его данные удалены.',
            'device_renamed' => 'Имя телефона изменено.',
            'lost_on' => 'Режим пропажи включён. Телефон подаст сигнал и покажет сообщение.',
            'lost_off' => 'Режим пропажи выключен.',
            'ring_sent' => 'Сигнал отправлен. Телефон зазвонит в течение минуты.',
            'geofence_saved' => 'Геозона сохранена.',
            'geofence_deleted' => 'Геозона удалена.',
            'geofence_bad' => 'Проверьте название и координаты геозоны.',
            'settings_saved' => 'Настройки сохранены.',
        );
        $base = admin_url('admin.php?page=family-guard');
        $tabs = array(
            'overview' => array('Обзор', 'dashicons-location-alt'),
            'device' => array('Телефон', 'dashicons-smartphone'),
            'geofences' => array('Геозоны', 'dashicons-admin-site-alt3'),
            'connect' => array('Подключение', 'dashicons-admin-network'),
            'settings' => array('Настройки', 'dashicons-admin-generic'),
        );
        $theme = $settings['theme'];
        ?>
        <div class="wrap">
        <div class="fg-app" data-theme="<?php echo esc_attr($theme); ?>">
            <?php $this->render_styles(); ?>
            <div class="fg-hero">
                <span class="fg-hero-ico" aria-hidden="true">🛡️</span>
                <div>
                    <h1>Family Guard</h1>
                    <p>Открытый родительский контроль и поиск телефона. Ребёнок всегда видит, что защита включена; снимок делается только после подтверждения на телефоне.</p>
                </div>
            </div>

            <nav class="fg-tabs">
                <?php foreach ($tabs as $key => $def):
                    $url = $key === 'device' && isset($_GET['device']) ? add_query_arg(array('tab' => $key, 'device' => absint($_GET['device'])), $base) : add_query_arg('tab', $key, $base); ?>
                    <a class="fg-tab <?php echo $tab === $key ? 'is-active' : ''; ?>" href="<?php echo esc_url($url); ?>">
                        <span class="dashicons <?php echo esc_attr($def[1]); ?>"></span><?php echo esc_html($def[0]); ?>
                    </a>
                <?php endforeach; ?>
            </nav>

            <?php if (isset($notices[$message])): ?>
                <div class="fg-flash <?php echo $message === 'geofence_bad' ? 'is-bad' : ''; ?>"><?php echo esc_html($notices[$message]); ?></div>
            <?php endif; ?>

            <?php
            switch ($tab) {
                case 'device': $this->render_tab_device($devices); break;
                case 'geofences': $this->render_tab_geofences($devices); break;
                case 'connect': $this->render_tab_connect(); break;
                case 'settings': $this->render_tab_settings($settings); break;
                default: $this->render_tab_overview($devices); break;
            }
            ?>
        </div>
        </div>
        <?php
    }

    private function leaflet_assets() {
        ?>
        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"
              integrity="sha256-p4NxAoJBhIIN+hmNHrzRCf9tD/miZyoHS5obTRR9BMY=" crossorigin="">
        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"
                integrity="sha256-20nQCchB9co0qIjJZRGuk2/Z9VM+kNiyxNV1lvTlZBo=" crossorigin=""></script>
        <?php
    }

    private function render_styles() {
        ?>
        <style>
        .fg-app{--p:#2563EB;--pd:#1E40AF;--ok:#16A34A;--warn:#F59E0B;--bad:#DC2626;
            --surface:#fff;--bg:#F1F5F9;--text:#0F172A;--text2:#64748B;--border:#e2e8f0;--shadow:0 2px 12px rgba(15,23,42,.07);
            max-width:1240px;color:var(--text);font-size:14px}
        .fg-app[data-theme=dark]{--p:#3B82F6;--pd:#1D4ED8;--ok:#22C55E;--warn:#FBBF24;--bad:#EF4444;
            --surface:#1E293B;--bg:#0F172A;--text:#F1F5F9;--text2:#94A3B8;--border:#334155;--shadow:0 2px 12px rgba(0,0,0,.35)}
        @media (prefers-color-scheme:dark){.fg-app[data-theme=auto]{--p:#3B82F6;--pd:#1D4ED8;--ok:#22C55E;--warn:#FBBF24;--bad:#EF4444;
            --surface:#1E293B;--bg:#0F172A;--text:#F1F5F9;--text2:#94A3B8;--border:#334155;--shadow:0 2px 12px rgba(0,0,0,.35)}}
        .fg-app *{box-sizing:border-box}
        .fg-hero{display:flex;align-items:center;gap:18px;background:linear-gradient(135deg,var(--p),var(--pd));color:#fff;border-radius:18px;padding:24px 28px;margin:14px 0 18px}
        .fg-hero-ico{font-size:40px;line-height:1}
        .fg-hero h1{color:#fff;margin:0;font-size:26px;font-weight:700}
        .fg-hero p{margin:5px 0 0;color:rgba(255,255,255,.88);font-size:14px;max-width:760px}
        .fg-tabs{display:flex;gap:6px;flex-wrap:wrap;border-bottom:1px solid var(--border);margin-bottom:20px}
        .fg-tab{display:inline-flex;align-items:center;gap:7px;padding:11px 18px;text-decoration:none;color:var(--text2);
            font-weight:600;border-bottom:3px solid transparent;margin-bottom:-1px;border-radius:8px 8px 0 0}
        .fg-tab:hover{color:var(--p);background:rgba(37,99,235,.06)}
        .fg-tab.is-active{color:var(--p);border-bottom-color:var(--p)}
        .fg-tab .dashicons{font-size:18px;width:18px;height:18px}
        .fg-flash{background:rgba(22,163,74,.12);border-left:4px solid var(--ok);color:var(--text);padding:12px 16px;border-radius:10px;margin-bottom:18px}
        .fg-flash.is-bad{background:rgba(220,38,38,.12);border-left-color:var(--bad)}
        .fg-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(320px,1fr));gap:18px}
        .fg-card{background:var(--surface);border:1px solid var(--border);border-radius:16px;padding:20px 22px;box-shadow:var(--shadow);margin-bottom:18px}
        .fg-card h2{margin:0 0 4px;font-size:18px;color:var(--text)}
        .fg-card h3{font-size:14px;margin:18px 0 8px;color:var(--text);text-transform:uppercase;letter-spacing:.04em}
        .fg-muted{color:var(--text2)}
        .fg-row{display:flex;align-items:center;justify-content:space-between;padding:8px 0;border-bottom:1px solid var(--border);gap:12px}
        .fg-row:last-child{border-bottom:0}
        .fg-row .lbl{display:flex;align-items:center;gap:8px;color:var(--text2)}
        .fg-row .val{font-weight:600;color:var(--text)}
        .fg-dot{display:inline-block;width:10px;height:10px;border-radius:50%;background:var(--ok)}
        .fg-dot.warn{background:var(--warn)}.fg-dot.bad{background:var(--bad)}
        .fg-badge{display:inline-flex;align-items:center;gap:6px;font-size:12px;font-weight:600;padding:3px 10px;border-radius:999px}
        .fg-badge.ok{background:rgba(22,163,74,.14);color:var(--ok)}
        .fg-badge.off{background:rgba(100,116,139,.16);color:var(--text2)}
        .fg-badge.bad{background:rgba(220,38,38,.14);color:var(--bad)}
        .fg-map{height:360px;border-radius:14px;overflow:hidden;border:1px solid var(--border);margin-bottom:16px}
        .fg-map-sm{height:260px}
        .fg-warn{background:rgba(245,158,11,.12);border-left:4px solid var(--warn);color:var(--text);padding:10px 14px;margin:8px 0;border-radius:10px}
        .fg-bad{background:rgba(220,38,38,.12);border-left:4px solid var(--bad);color:var(--text);padding:10px 14px;margin:8px 0;border-radius:10px}
        .fg-ok{background:rgba(22,163,74,.12);border-left:4px solid var(--ok);color:var(--text);padding:10px 14px;margin:8px 0;border-radius:10px}
        .fg-btn{display:inline-flex;align-items:center;gap:7px;border:0;border-radius:10px;padding:10px 16px;font-weight:600;font-size:13px;cursor:pointer;text-decoration:none;line-height:1.2}
        .fg-btn.primary{background:var(--p);color:#fff}.fg-btn.primary:hover{background:var(--pd);color:#fff}
        .fg-btn.ghost{background:transparent;color:var(--p);border:1px solid var(--p)}
        .fg-btn.danger{background:transparent;color:var(--bad);border:1px solid var(--bad)}
        .fg-btn.danger:hover{background:var(--bad);color:#fff}
        .fg-actions{display:flex;gap:10px;flex-wrap:wrap;align-items:flex-start;margin:6px 0}
        .fg-app input[type=text],.fg-app input[type=number],.fg-app input[type=email],.fg-app textarea,.fg-app select{
            border:1px solid var(--border);border-radius:9px;padding:9px 12px;background:var(--surface);color:var(--text);font-size:14px;max-width:100%}
        .fg-field{margin:10px 0}.fg-field label{display:block;font-weight:600;margin-bottom:5px}
        .fg-code{font:700 38px/1.1 ui-monospace,SFMono-Regular,monospace;letter-spacing:8px;color:var(--p);margin:8px 0}
        .fg-apps .fg-row .val{font-variant-numeric:tabular-nums}
        .fg-bar{height:8px;border-radius:999px;background:var(--border);overflow:hidden;margin-top:4px}
        .fg-bar>span{display:block;height:100%;background:var(--p)}
        .fg-bar.over>span{background:var(--bad)}
        .fg-snapshot{max-width:100%;height:auto;border-radius:12px;margin-top:10px;border:1px solid var(--border)}
        .fg-dev-head{display:flex;justify-content:space-between;align-items:flex-start;gap:12px;flex-wrap:wrap}
        .fg-pick{display:flex;align-items:center;gap:8px;color:var(--text2);text-decoration:none;padding:6px 12px;border:1px solid var(--border);border-radius:999px}
        .fg-pick.is-active{border-color:var(--p);color:var(--p);background:rgba(37,99,235,.06)}
        .fg-event{display:flex;gap:10px;padding:9px 0;border-bottom:1px solid var(--border)}
        .fg-event:last-child{border-bottom:0}
        .fg-event .when{color:var(--text2);font-size:12px;white-space:nowrap}
        .fg-empty{text-align:center;padding:30px 20px;color:var(--text2)}
        .fg-empty .big{font-size:38px}
        </style>
        <?php
    }

    private function status_badge($device, $latest) {
        $warnings = $this->device_warnings($device, $latest);
        if (!$latest) return '<span class="fg-badge off">Нет данных</span>';
        $offline = false;
        foreach ($warnings as $w) { if (mb_strpos($w, 'не выходил на связь') !== false) $offline = true; }
        if ($offline) return '<span class="fg-badge off">Офлайн</span>';
        if ($warnings) return '<span class="fg-badge bad">' . count($warnings) . ' предупр.</span>';
        return '<span class="fg-badge ok"><span class="fg-dot"></span>Онлайн</span>';
    }

    private function render_tab_overview($devices) {
        global $wpdb;
        $this->leaflet_assets();
        $points = array();
        $all_warnings = array();
        foreach ($devices as $device) {
            $latest = $wpdb->get_row($wpdb->prepare(
                "SELECT * FROM {$this->telemetry_table} WHERE device_id = %d ORDER BY id DESC LIMIT 1", $device->id
            ));
            if ($latest && $latest->latitude !== null && $latest->longitude !== null) {
                $points[] = array('name' => $device->name, 'lat' => (float) $latest->latitude, 'lon' => (float) $latest->longitude,
                    'battery' => (int) $latest->battery, 'at' => $device->last_seen ? get_date_from_gmt($device->last_seen, 'd.m H:i') : '—');
            }
            foreach ($this->device_warnings($device, $latest) as $w) {
                $all_warnings[] = array('name' => $device->name, 'text' => $w);
            }
        }
        if (!$devices): ?>
            <div class="fg-card"><div class="fg-empty">
                <div class="big">📱</div>
                <h2>Пока нет подключённых телефонов</h2>
                <p>Откройте вкладку «Подключение», создайте код и отсканируйте QR в приложении ребёнка.</p>
                <a class="fg-btn primary" href="<?php echo esc_url(add_query_arg('tab', 'connect', admin_url('admin.php?page=family-guard'))); ?>">Подключить телефон</a>
            </div></div>
        <?php return; endif; ?>

        <?php if ($all_warnings): ?>
            <div class="fg-card">
                <h2>Требует внимания</h2>
                <?php foreach (array_slice($all_warnings, 0, 8) as $w): ?>
                    <div class="fg-warn"><strong><?php echo esc_html($w['name']); ?>:</strong> <?php echo esc_html($w['text']); ?></div>
                <?php endforeach; ?>
            </div>
        <?php endif; ?>

        <div class="fg-card">
            <h2>Карта семьи</h2>
            <div id="fg-map-overview" class="fg-map"></div>
            <?php if (!$points): ?><p class="fg-muted">Как только телефон пришлёт геолокацию, он появится на карте.</p><?php endif; ?>
        </div>
        <script>
        (function(){
            if(!window.L) return;
            var pts = <?php echo wp_json_encode($points); ?>;
            var map = L.map('fg-map-overview').setView([55.75,37.61], 4);
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
            var group=[];
            pts.forEach(function(p){
                var m=L.marker([p.lat,p.lon]).addTo(map);
                m.bindPopup('<b>'+p.name+'</b><br>Заряд: '+p.battery+'%<br>'+p.at);
                group.push([p.lat,p.lon]);
            });
            if(group.length===1) map.setView(group[0],15);
            else if(group.length>1) map.fitBounds(group,{padding:[40,40]});
        })();
        </script>

        <h2 style="margin:4px 0 12px">Телефоны</h2>
        <div class="fg-grid">
            <?php foreach ($devices as $device):
                $latest = $wpdb->get_row($wpdb->prepare(
                    "SELECT * FROM {$this->telemetry_table} WHERE device_id = %d ORDER BY id DESC LIMIT 1", $device->id
                ));
                $url = add_query_arg(array('tab' => 'device', 'device' => $device->id), admin_url('admin.php?page=family-guard'));
            ?>
                <section class="fg-card">
                    <div class="fg-dev-head">
                        <h2><?php echo esc_html($device->name); ?></h2>
                        <?php echo $this->status_badge($device, $latest); ?>
                    </div>
                    <p class="fg-muted">Связь: <?php echo $device->last_seen ? esc_html(get_date_from_gmt($device->last_seen, 'd.m.Y H:i')) : '—'; ?></p>
                    <?php if ($latest): ?>
                        <div class="fg-row"><span class="lbl"><span class="dashicons dashicons-battery"></span>Заряд</span><span class="val"><?php echo esc_html((int) $latest->battery); ?>%</span></div>
                        <?php if ($latest->screen_today_ms !== null): $lim=(int)$latest->daily_limit_minutes; $used=round(((int)$latest->screen_today_ms)/60000); $pct=$lim>0?min(100,round($used/$lim*100)):0; ?>
                            <div class="fg-row"><span class="lbl"><span class="dashicons dashicons-clock"></span>Экран сегодня</span><span class="val"><?php echo esc_html($this->format_minutes($latest->screen_today_ms)); ?><?php if($lim>0):?> / <?php echo esc_html($lim);?> мин<?php endif;?></span></div>
                            <?php if($lim>0): ?><div class="fg-bar <?php echo $used>$lim?'over':'';?>"><span style="width:<?php echo esc_attr($pct);?>%"></span></div><?php endif; ?>
                        <?php endif; ?>
                        <?php if ($latest->latitude !== null): ?>
                            <div class="fg-row"><span class="lbl"><span class="dashicons dashicons-location"></span>Геолокация</span><span class="val"><?php echo esc_html(round((float)$latest->latitude,4)); ?>, <?php echo esc_html(round((float)$latest->longitude,4)); ?></span></div>
                        <?php endif; ?>
                    <?php else: ?>
                        <div class="fg-warn">Данные ещё не пришли. Проверьте запуск защиты и разрешения на телефоне.</div>
                    <?php endif; ?>
                    <p style="margin-top:14px"><a class="fg-btn ghost" href="<?php echo esc_url($url); ?>">Открыть телефон</a></p>
                </section>
            <?php endforeach; ?>
        </div>
        <?php
    }

    private function render_tab_device($devices) {
        global $wpdb;
        if (!$devices) {
            echo '<div class="fg-card"><div class="fg-empty"><div class="big">📱</div><h2>Нет телефонов</h2><p>Сначала подключите телефон во вкладке «Подключение».</p></div></div>';
            return;
        }
        $device_id = isset($_GET['device']) ? absint($_GET['device']) : (int) $devices[0]->id;
        $device = null;
        foreach ($devices as $d) { if ((int) $d->id === $device_id) $device = $d; }
        if (!$device) { $device = $devices[0]; $device_id = (int) $device->id; }

        // Переключатель телефонов.
        echo '<div class="fg-actions" style="margin-bottom:16px">';
        foreach ($devices as $d) {
            $u = add_query_arg(array('tab' => 'device', 'device' => $d->id), admin_url('admin.php?page=family-guard'));
            printf('<a class="fg-pick %s" href="%s"><span class="dashicons dashicons-smartphone"></span>%s</a>',
                (int) $d->id === $device_id ? 'is-active' : '', esc_url($u), esc_html($d->name));
        }
        echo '</div>';

        $latest = $wpdb->get_row($wpdb->prepare(
            "SELECT * FROM {$this->telemetry_table} WHERE device_id = %d ORDER BY id DESC LIMIT 1", $device_id
        ));
        $apps = $latest ? json_decode($latest->apps_json, true) : array();
        $perms = $latest ? json_decode((string) $latest->perms_json, true) : array();
        $warnings = $this->device_warnings($device, $latest);
        $history = $this->history_points($device_id);
        $fences = $wpdb->get_results($wpdb->prepare("SELECT * FROM {$this->geofences_table} WHERE device_id = %d", $device_id));
        $events = $wpdb->get_results($wpdb->prepare("SELECT * FROM {$this->events_table} WHERE device_id = %d ORDER BY id DESC LIMIT 20", $device_id));
        $this->leaflet_assets();
        ?>
        <div class="fg-card">
            <div class="fg-dev-head">
                <div>
                    <h2><?php echo esc_html($device->name); ?></h2>
                    <p class="fg-muted"><?php echo esc_html(trim($device->model . ' · Android ' . $device->android_version . ($device->app_version ? ' · приложение ' . $device->app_version : ''), ' ·')); ?><br>
                        Последняя связь: <?php echo $device->last_seen ? esc_html(get_date_from_gmt($device->last_seen, 'd.m.Y H:i')) : '—'; ?></p>
                </div>
                <?php echo $this->status_badge($device, $latest); ?>
            </div>
            <?php if ($warnings): foreach ($warnings as $w): ?><div class="fg-warn"><?php echo esc_html($w); ?></div><?php endforeach; else: ?>
                <div class="fg-ok">Защита работает, все разрешения включены.</div>
            <?php endif; ?>
        </div>

        <?php if (!$latest): ?>
            <div class="fg-card"><div class="fg-bad">
                <strong>Данные с телефона ещё не пришли.</strong><br>
                Проверьте на телефоне: 1) подключение к сайту выполнено; 2) разрешена геолокация («Разрешить всегда») и статистика приложений; 3) нажата кнопка «Запустить защиту»; 4) отключена экономия батареи. Первый отчёт приходит в течение минуты.
            </div></div>
        <?php endif; ?>

        <div class="fg-grid">
            <section class="fg-card">
                <h2>Местоположение и маршрут</h2>
                <div id="fg-map-device" class="fg-map fg-map-sm"></div>
                <?php if (!$history): ?><p class="fg-muted">Точек пока нет.</p><?php endif; ?>
                <p><a class="fg-btn ghost" href="<?php echo esc_url(wp_nonce_url(add_query_arg(array('action'=>'fg_export','device'=>$device_id), admin_url('admin-post.php')), 'fg_export_'.$device_id)); ?>">Экспорт истории (CSV)</a></p>
            </section>

            <section class="fg-card">
                <h2>Состояние</h2>
                <?php if ($latest): ?>
                    <div class="fg-row"><span class="lbl">Заряд</span><span class="val"><?php echo esc_html((int)$latest->battery); ?>%</span></div>
                    <?php if ($latest->screen_today_ms !== null): $lim=(int)$latest->daily_limit_minutes; $used=round(((int)$latest->screen_today_ms)/60000); ?>
                        <div class="fg-row"><span class="lbl">Экран сегодня</span><span class="val"><?php echo esc_html($this->format_minutes($latest->screen_today_ms)); ?><?php if($lim>0):?> / <?php echo esc_html($lim);?> мин<?php endif;?></span></div>
                        <?php if($lim>0): $pct=min(100,round($used/$lim*100)); ?><div class="fg-bar <?php echo $used>$lim?'over':'';?>"><span style="width:<?php echo esc_attr($pct);?>%"></span></div><?php endif; ?>
                    <?php endif; ?>
                    <h3>Разрешения</h3>
                    <?php foreach (self::PERMISSION_LABELS as $pk => $pl): if (!is_array($perms) || !array_key_exists($pk, $perms)) continue; ?>
                        <div class="fg-row"><span class="lbl"><?php echo esc_html($pl); ?></span><span class="val"><?php echo $perms[$pk] ? '<span class="fg-badge ok">включено</span>' : '<span class="fg-badge bad">выключено</span>'; ?></span></div>
                    <?php endforeach; ?>
                <?php else: ?><p class="fg-muted">Нет данных.</p><?php endif; ?>
            </section>
        </div>

        <?php if ($apps): ?>
            <section class="fg-card fg-apps">
                <h2>Экранное время по приложениям</h2>
                <p class="fg-muted">Сегодня, по убыванию. Так видно, сколько времени уходит на YouTube, игры и прочее.</p>
                <?php $max = 0; foreach ($apps as $a) { $max = max($max, (int) $a['foreground_ms']); }
                foreach (array_slice($apps, 0, 20) as $app): $ms=(int)$app['foreground_ms']; $w=$max>0?round($ms/$max*100):0; ?>
                    <div class="fg-row"><span class="lbl"><?php echo esc_html($app['label'] ?? $app['package']); ?></span><span class="val"><?php echo esc_html($this->format_minutes($ms)); ?></span></div>
                    <div class="fg-bar"><span style="width:<?php echo esc_attr($w); ?>%"></span></div>
                <?php endforeach; ?>
            </section>
        <?php endif; ?>

        <?php $is_lost = $this->lost_state($device_id);
        $snapshot_command = $wpdb->get_row($wpdb->prepare(
            "SELECT id, result_json FROM {$this->commands_table} WHERE device_id = %d AND command_type = 'camera_snapshot' AND result_json IS NOT NULL ORDER BY id DESC LIMIT 1", $device_id));
        $snapshot_result = $snapshot_command ? json_decode($snapshot_command->result_json, true) : array();
        $snapshot_file = is_array($snapshot_result) ? (string) ($snapshot_result['snapshot_file'] ?? '') : '';
        $snapshot_cmd_id = $snapshot_command ? (int) $snapshot_command->id : 0;
        $aurl = esc_url(admin_url('admin-post.php')); ?>

        <section class="fg-card">
            <h2>Поиск телефона (антивор)</h2>
            <p class="fg-muted">Громкий сигнал и сообщение на весь экран. Снимок камеры при этом не делается.</p>
            <?php if ($is_lost): ?><div class="fg-bad"><strong>Режим пропажи включён.</strong> Геолокация обновляется чаще обычного.</div><?php endif; ?>
            <div class="fg-actions">
                <form method="post" action="<?php echo $aurl; ?>">
                    <input type="hidden" name="action" value="fg_ring"><input type="hidden" name="device_id" value="<?php echo esc_attr($device_id); ?>">
                    <?php wp_nonce_field('fg_ring'); ?><button class="fg-btn ghost" type="submit">🔔 Подать звуковой сигнал</button>
                </form>
                <?php if ($is_lost): ?>
                    <form method="post" action="<?php echo $aurl; ?>">
                        <input type="hidden" name="action" value="fg_lost_off"><input type="hidden" name="device_id" value="<?php echo esc_attr($device_id); ?>">
                        <?php wp_nonce_field('fg_lost_off'); ?><button class="fg-btn ghost" type="submit">Выключить режим пропажи</button>
                    </form>
                <?php endif; ?>
            </div>
            <?php if (!$is_lost): ?>
                <form method="post" action="<?php echo $aurl; ?>" style="margin-top:10px">
                    <input type="hidden" name="action" value="fg_lost_on"><input type="hidden" name="device_id" value="<?php echo esc_attr($device_id); ?>">
                    <?php wp_nonce_field('fg_lost_on'); ?>
                    <div class="fg-field"><input type="text" name="lost_message" placeholder="Сообщение нашедшему (необязательно)" maxlength="200" style="width:320px"></div>
                    <div class="fg-field"><input type="text" name="lost_phone" placeholder="Телефон для связи" maxlength="20"></div>
                    <button class="fg-btn primary" type="submit">Включить режим пропажи</button>
                </form>
            <?php endif; ?>
        </section>

        <section class="fg-card">
            <h2>Действия</h2>
            <div class="fg-actions">
                <form method="post" action="<?php echo $aurl; ?>">
                    <input type="hidden" name="action" value="fg_camera_request"><input type="hidden" name="device_id" value="<?php echo esc_attr($device_id); ?>">
                    <?php wp_nonce_field('fg_camera_request'); ?><button class="fg-btn ghost" type="submit">📸 Запросить видимый снимок</button>
                </form>
            </div>
            <form method="post" action="<?php echo $aurl; ?>" style="margin-top:14px">
                <input type="hidden" name="action" value="fg_block_apps"><input type="hidden" name="device_id" value="<?php echo esc_attr($device_id); ?>">
                <?php wp_nonce_field('fg_block_apps'); ?>
                <div class="fg-field"><label>Заблокированные пакеты (только версия full)</label>
                    <textarea name="packages" rows="3" style="width:100%" placeholder="com.whatsapp, com.instagram.android"></textarea></div>
                <p class="fg-muted">Чтобы ограничить WhatsApp, добавьте <code>com.whatsapp</code>. Пустой список снимает ограничения.</p>
                <button class="fg-btn ghost" type="submit">Отправить список</button>
            </form>
            <?php if ($snapshot_file && $snapshot_cmd_id): ?>
                <h3>Последний подтверждённый снимок</h3>
                <p class="fg-muted">Хранится в закрытой папке, доступен только администратору.</p>
                <a href="<?php echo esc_url($this->snapshot_url($snapshot_cmd_id)); ?>" target="_blank" rel="noopener"><img class="fg-snapshot" src="<?php echo esc_url($this->snapshot_url($snapshot_cmd_id)); ?>" alt=""></a>
            <?php endif; ?>
        </section>

        <div class="fg-grid">
            <section class="fg-card">
                <h2>Лента событий</h2>
                <?php if (!$events): ?><p class="fg-muted">Событий пока нет. Здесь появятся входы/выходы из геозон, низкий заряд, превышение лимита и сигналы SOS.</p><?php endif; ?>
                <?php foreach ($events as $ev): ?>
                    <div class="fg-event"><span class="dashicons dashicons-marker"></span><div style="flex:1"><?php echo esc_html($ev->message); ?><br><span class="when"><?php echo esc_html(get_date_from_gmt($ev->created_at, 'd.m.Y H:i')); ?></span></div></div>
                <?php endforeach; ?>
            </section>
            <section class="fg-card">
                <h2>Управление</h2>
                <form method="post" action="<?php echo $aurl; ?>">
                    <input type="hidden" name="action" value="fg_rename_device"><input type="hidden" name="device_id" value="<?php echo esc_attr($device_id); ?>">
                    <?php wp_nonce_field('fg_rename_device'); ?>
                    <div class="fg-field"><label>Имя телефона</label><input type="text" name="name" value="<?php echo esc_attr($device->name); ?>" maxlength="190" style="width:280px"></div>
                    <button class="fg-btn ghost" type="submit">Переименовать</button>
                </form>
                <form method="post" action="<?php echo $aurl; ?>" style="margin-top:18px" onsubmit="return confirm('Удалить этот телефон и все его данные?');">
                    <input type="hidden" name="action" value="fg_remove_device"><input type="hidden" name="device_id" value="<?php echo esc_attr($device_id); ?>">
                    <?php wp_nonce_field('fg_remove_device'); ?>
                    <button class="fg-btn danger" type="submit">Удалить телефон и данные</button>
                </form>
            </section>
        </div>

        <script>
        (function(){
            if(!window.L) return;
            var hist = <?php echo wp_json_encode($history); ?>;
            var fences = <?php echo wp_json_encode(array_map(function($f){return array('name'=>$f->name,'lat'=>(float)$f->latitude,'lon'=>(float)$f->longitude,'r'=>(int)$f->radius_m);}, $fences)); ?>;
            var map = L.map('fg-map-device').setView([55.75,37.61], 4);
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
            var bounds=[];
            fences.forEach(function(f){ L.circle([f.lat,f.lon],{radius:f.r,color:'#2563EB',fillOpacity:.08}).addTo(map).bindPopup(f.name); bounds.push([f.lat,f.lon]); });
            if(hist.length){
                var line=hist.map(function(p){return [p.lat,p.lon];});
                L.polyline(line,{color:'#2563EB',weight:3,opacity:.7}).addTo(map);
                var last=hist[hist.length-1];
                L.marker([last.lat,last.lon]).addTo(map).bindPopup('Последняя точка<br>'+last.at).openPopup();
                line.forEach(function(c){bounds.push(c);});
            }
            if(bounds.length===1) map.setView(bounds[0],15);
            else if(bounds.length>1) map.fitBounds(bounds,{padding:[30,30]});
        })();
        </script>
        <?php
    }

    private function render_tab_geofences($devices) {
        global $wpdb;
        if (!$devices) {
            echo '<div class="fg-card"><div class="fg-empty"><div class="big">📍</div><h2>Нет телефонов</h2><p>Геозоны привязываются к телефону. Сначала подключите телефон.</p></div></div>';
            return;
        }
        $this->leaflet_assets();
        $fences = $wpdb->get_results("SELECT g.*, d.name AS device_name FROM {$this->geofences_table} g INNER JOIN {$this->devices_table} d ON d.id = g.device_id ORDER BY g.id DESC");
        $aurl = esc_url(admin_url('admin-post.php'));
        ?>
        <div class="fg-card">
            <h2>Новая геозона</h2>
            <p class="fg-muted">Отметьте место на карте (клик) и задайте радиус. Когда телефон войдёт в зону или выйдет — в ленте событий появится запись, а при включённых письмах придёт уведомление.</p>
            <div id="fg-map-geo" class="fg-map fg-map-sm"></div>
            <form method="post" action="<?php echo $aurl; ?>">
                <input type="hidden" name="action" value="fg_save_geofence">
                <?php wp_nonce_field('fg_save_geofence'); ?>
                <input type="hidden" name="latitude" id="fg-geo-lat" value="">
                <input type="hidden" name="longitude" id="fg-geo-lon" value="">
                <div class="fg-grid" style="grid-template-columns:repeat(auto-fill,minmax(220px,1fr))">
                    <div class="fg-field"><label>Телефон</label>
                        <select name="device_id" style="width:100%"><?php foreach ($devices as $d): ?><option value="<?php echo esc_attr($d->id); ?>"><?php echo esc_html($d->name); ?></option><?php endforeach; ?></select></div>
                    <div class="fg-field"><label>Название</label><input type="text" name="name" placeholder="Дом, Школа…" maxlength="190" style="width:100%" required></div>
                    <div class="fg-field"><label>Радиус, м</label><input type="number" name="radius_m" id="fg-geo-rad" value="200" min="50" max="10000" style="width:100%"></div>
                </div>
                <p><label style="margin-right:16px"><input type="checkbox" name="notify_enter" checked> уведомлять о входе</label>
                   <label><input type="checkbox" name="notify_exit" checked> уведомлять о выходе</label></p>
                <p class="fg-muted" id="fg-geo-coords">Координаты: не выбраны (кликните по карте)</p>
                <button class="fg-btn primary" type="submit">Сохранить геозону</button>
            </form>
        </div>

        <div class="fg-card">
            <h2>Геозоны</h2>
            <?php if (!$fences): ?><p class="fg-muted">Пока не создано ни одной геозоны.</p><?php endif; ?>
            <?php foreach ($fences as $f): ?>
                <div class="fg-row">
                    <span class="lbl"><span class="dashicons dashicons-admin-site-alt3"></span><strong><?php echo esc_html($f->name); ?></strong> · <?php echo esc_html($f->device_name); ?> · <?php echo esc_html((int)$f->radius_m); ?> м</span>
                    <form method="post" action="<?php echo $aurl; ?>" onsubmit="return confirm('Удалить геозону?');">
                        <input type="hidden" name="action" value="fg_delete_geofence"><input type="hidden" name="geofence_id" value="<?php echo esc_attr($f->id); ?>">
                        <?php wp_nonce_field('fg_delete_geofence'); ?><button class="fg-btn danger" type="submit">Удалить</button>
                    </form>
                </div>
            <?php endforeach; ?>
        </div>

        <script>
        (function(){
            if(!window.L) return;
            var map=L.map('fg-map-geo').setView([55.75,37.61],4);
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
            var marker=null, circle=null;
            function setRadius(){ if(circle){ circle.setRadius(parseInt(document.getElementById('fg-geo-rad').value,10)||200); } }
            document.getElementById('fg-geo-rad').addEventListener('input', setRadius);
            map.on('click', function(e){
                var lat=e.latlng.lat, lon=e.latlng.lng;
                document.getElementById('fg-geo-lat').value=lat.toFixed(6);
                document.getElementById('fg-geo-lon').value=lon.toFixed(6);
                document.getElementById('fg-geo-coords').textContent='Координаты: '+lat.toFixed(5)+', '+lon.toFixed(5);
                if(!marker){ marker=L.marker([lat,lon]).addTo(map); circle=L.circle([lat,lon],{radius:200,color:'#2563EB',fillOpacity:.08}).addTo(map); }
                else { marker.setLatLng([lat,lon]); circle.setLatLng([lat,lon]); }
                setRadius();
            });
        })();
        </script>
        <?php
    }

    private function render_tab_connect() {
        $pairing = get_option(self::OPTION_PAIRING, array());
        ?>
        <div class="fg-card" style="max-width:520px">
            <h2>Подключить телефон</h2>
            <?php if (!empty($pairing['display']) && time() < (int) ($pairing['expires'] ?? 0)):
                $fg_payload = add_query_arg(array('site' => home_url(), 'code' => $pairing['display']), 'familyguard://pair'); ?>
                <p class="fg-code"><?php echo esc_html($pairing['display']); ?></p>
                <p class="fg-muted">Действует до <?php echo esc_html(wp_date('H:i', (int) $pairing['expires'])); ?> и только для одного телефона.</p>
                <div id="fg-qr" style="margin:12px 0;display:inline-block;background:#fff;padding:12px;border-radius:12px"></div>
                <script src="<?php echo esc_url(plugin_dir_url(__FILE__) . 'assets/qrcode.min.js'); ?>"></script>
                <script>(function(){var el=document.getElementById('fg-qr');if(el&&window.QRCode){new QRCode(el,{text:<?php echo wp_json_encode($fg_payload); ?>,width:190,height:190,correctLevel:QRCode.CorrectLevel.M});}})();</script>
            <?php else: ?>
                <p class="fg-muted">Создайте код, затем введите его в приложении ребёнка или отсканируйте QR.</p>
            <?php endif; ?>
            <form method="post" action="<?php echo esc_url(admin_url('admin-post.php')); ?>">
                <input type="hidden" name="action" value="fg_generate_pairing">
                <?php wp_nonce_field('fg_generate_pairing'); ?>
                <button class="fg-btn primary" type="submit">Создать новый код</button>
            </form>
        </div>
        <div class="fg-card" style="max-width:520px">
            <h2>Как подключить</h2>
            <div class="fg-row"><span class="lbl"><strong>1</strong></span><span class="val" style="font-weight:400;color:var(--text)">Создайте одноразовый код кнопкой выше.</span></div>
            <div class="fg-row"><span class="lbl"><strong>2</strong></span><span class="val" style="font-weight:400;color:var(--text)">В приложении ребёнка нажмите «Сканировать QR-код» и наведите камеру на код.</span></div>
            <div class="fg-row"><span class="lbl"><strong>3</strong></span><span class="val" style="font-weight:400;color:var(--text)">Выдайте разрешения и нажмите «Запустить защиту». Телефон появится во вкладке «Обзор».</span></div>
        </div>
        <?php
    }

    private function render_tab_settings($settings) {
        ?>
        <div class="fg-card" style="max-width:620px">
            <h2>Настройки панели</h2>
            <form method="post" action="<?php echo esc_url(admin_url('admin-post.php')); ?>">
                <input type="hidden" name="action" value="fg_save_settings">
                <?php wp_nonce_field('fg_save_settings'); ?>
                <div class="fg-field"><label>Порог низкого заряда, %</label>
                    <input type="number" name="low_battery" value="<?php echo esc_attr((int) $settings['low_battery']); ?>" min="1" max="100">
                    <p class="fg-muted">Ниже этого значения появится предупреждение и, если включено, придёт письмо.</p></div>
                <div class="fg-field"><label><input type="checkbox" name="email_enabled" <?php checked(!empty($settings['email_enabled'])); ?>> Присылать уведомления на почту</label>
                    <p class="fg-muted">Тревоги: геозоны, низкий заряд, превышение лимита, SOS.</p></div>
                <div class="fg-field"><label>Почта для уведомлений</label>
                    <input type="email" name="email_to" value="<?php echo esc_attr($settings['email_to']); ?>" style="width:320px"></div>
                <div class="fg-field"><label>Тема оформления</label>
                    <select name="theme">
                        <option value="auto" <?php selected($settings['theme'], 'auto'); ?>>Как в системе</option>
                        <option value="light" <?php selected($settings['theme'], 'light'); ?>>Светлая</option>
                        <option value="dark" <?php selected($settings['theme'], 'dark'); ?>>Тёмная</option>
                    </select></div>
                <button class="fg-btn primary" type="submit">Сохранить</button>
            </form>
        </div>
        <div class="fg-card" style="max-width:620px">
            <h2>О системе</h2>
            <div class="fg-row"><span class="lbl">Версия плагина</span><span class="val"><?php echo esc_html(self::VERSION); ?></span></div>
            <div class="fg-row"><span class="lbl">Снимок с камеры</span><span class="val" style="font-weight:400">только после подтверждения на телефоне</span></div>
            <div class="fg-row"><span class="lbl">Скрытая съёмка/прослушка</span><span class="val" style="font-weight:400">не используется</span></div>
        </div>
        <?php
    }
}

register_activation_hook(__FILE__, array('Family_Guard_Plugin', 'activate'));
Family_Guard_Plugin::instance();
