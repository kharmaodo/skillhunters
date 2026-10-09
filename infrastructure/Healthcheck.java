class Healthcheck {
    public static void main(String[] args) throws Exception {
        var connection = (java.net.HttpURLConnection) java.net.URI.create("http://127.0.0.1:8080/actuator/health/readiness").toURL().openConnection();
        connection.setConnectTimeout(2000); connection.setReadTimeout(2000);
        System.exit(connection.getResponseCode() == 200 ? 0 : 1);
    }
}
