import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * EV Charging Station Booking & Billing System (web version, pure Java).
 * Run it, then Ctrl+Click the link printed in the terminal: http://localhost:8080
 */
public class EVChargingSystem {

    static final int PORT = 8080;
    static final Object LOCK = new Object();

    // ---------- Model ----------
    static class Station {
        int id; String name, location, type;
        double powerKw, rate; int slots;

        Station(int id, String name, String location, String type,
                double powerKw, double rate, int slots) {
            this.id = id; this.name = name; this.location = location;
            this.type = type; this.powerKw = powerKw;
            this.rate = rate; this.slots = slots;
        }
    }

    static class Booking {
        static int counter = 1;
        int id; String user, date, time, status = "BOOKED";
        Station station; double hours; boolean paid = false;

        Booking(String user, Station station, String date, String time, double hours) {
            this.id = counter++; this.user = user; this.station = station;
            this.date = date; this.time = time; this.hours = hours;
        }

        double kwh()    { return Math.round(station.powerKw * hours * 0.8 * 100) / 100.0; }
        double energy() { return Math.round(kwh() * station.rate * 100) / 100.0; }
        double gst()    { return Math.round(energy() * 0.18 * 100) / 100.0; }
        double total()  { return Math.round((energy() + gst()) * 100) / 100.0; }
    }

    static final List<Station> stations = new ArrayList<>();
    static final List<Booking> bookings = new ArrayList<>();

    static void loadStations() {
        stations.add(new Station(1, "City Mall EV Hub", "T Nagar", "Fast DC (50kW)", 50, 18, 4));
        stations.add(new Station(2, "Airport Charge Point", "Meenambakkam", "Fast DC (60kW)", 60, 20, 3));
        stations.add(new Station(3, "OMR Green Station", "OMR", "AC (7kW)", 7, 12, 5));
    }

    static Station findStation(int id) {
        for (Station s : stations) if (s.id == id) return s;
        return null;
    }

    static Booking findBooking(int id) {
        for (Booking b : bookings) if (b.id == id) return b;
        return null;
    }

    // ---------- Utilities ----------
    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String money(double d) { return String.format(Locale.US, "%.2f", d); }

    static int toInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }

    static double toDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return -1; }
    }

    static Map<String, String> parse(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = i >= 0 ? pair.substring(0, i) : pair;
            String v = i >= 0 ? pair.substring(i + 1) : "";
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    static void send(HttpExchange ex, int code, String html) throws IOException {
        byte[] data = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(303, -1);
        ex.close();
    }

    static String flash(String msg) {
        return "/?msg=" + URLEncoder.encode(msg, StandardCharsets.UTF_8);
    }

    // ---------- HTML Pages ----------
    static String page(String title, String body) {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
            + "<title>" + esc(title) + " - EV Charging</title><style>"
            + "*{box-sizing:border-box}"
            + "body{font-family:Segoe UI,Arial,sans-serif;background:#f1f8f4;margin:0;color:#1b2b22}"
            + "header{background:#0f7a4a;color:#fff;padding:16px 28px;display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap}"
            + "header h1{margin:0;font-size:22px}"
            + "nav a{color:#fff;text-decoration:none;margin-left:18px;font-weight:600}"
            + "nav a:hover{text-decoration:underline}"
            + "main{max-width:980px;margin:26px auto;padding:0 16px}"
            + ".card{background:#fff;border-radius:12px;padding:22px;box-shadow:0 2px 8px rgba(0,0,0,.08);margin-bottom:20px}"
            + "h2{margin-top:0}"
            + "table{width:100%;border-collapse:collapse}"
            + "th,td{padding:10px 12px;text-align:left;border-bottom:1px solid #e3ece6}"
            + "th{background:#e6f3ec}"
            + ".btn{background:#0f7a4a;color:#fff;border:none;padding:9px 18px;border-radius:8px;cursor:pointer;font-size:14px;text-decoration:none;display:inline-block}"
            + ".btn:hover{background:#0b5f39}"
            + ".btn.red{background:#c0392b}.btn.red:hover{background:#962d22}"
            + ".btn.gray{background:#7f8c8d}"
            + ".btn.disabled{background:#b5c0ba;pointer-events:none}"
            + "label{display:block;margin:12px 0 4px;font-weight:600}"
            + "input{width:100%;padding:10px;border:1px solid #b9cbc0;border-radius:8px;font-size:15px}"
            + ".msg{background:#fff3cd;border:1px solid #ffe08a;padding:12px 16px;border-radius:8px;margin-bottom:18px}"
            + ".badge{padding:3px 10px;border-radius:12px;font-size:12px;font-weight:700;color:#fff}"
            + ".BOOKED{background:#2980b9}.COMPLETED{background:#27ae60}.CANCELLED{background:#c0392b}"
            + ".pending{background:#e67e22}.paid{background:#27ae60}"
            + ".invoice td:last-child{text-align:right}"
            + ".total td{font-size:18px;font-weight:700;border-top:2px solid #0f7a4a}"
            + ".actions form{display:inline-block;margin-right:8px}"
            + "@media print{header,.actions{display:none}}"
            + "</style></head><body>"
            + "<header><h1>&#9889; EV Charging Station System</h1><nav>"
            + "<a href='/'>Stations</a><a href='/bookings'>All Bookings</a></nav></header>"
            + "<main>" + body + "</main></body></html>";
    }

    static String homePage(String msg) {
        StringBuilder sb = new StringBuilder();
        if (msg != null && !msg.isEmpty()) sb.append("<div class='msg'>").append(esc(msg)).append("</div>");
        sb.append("<div class='card'><h2>Available Charging Stations</h2><table>")
          .append("<tr><th>ID</th><th>Name</th><th>Location</th><th>Charger</th><th>&#8377;/kWh</th><th>Slots</th><th></th></tr>");
        for (Station s : stations) {
            sb.append("<tr><td>").append(s.id).append("</td><td>").append(esc(s.name)).append("</td><td>")
              .append(esc(s.location)).append("</td><td>").append(esc(s.type)).append("</td><td>")
              .append(money(s.rate)).append("</td><td>").append(s.slots).append("</td><td>");
            if (s.slots > 0) sb.append("<a class='btn' href='/book?id=").append(s.id).append("'>Book</a>");
            else sb.append("<span class='btn disabled'>Full</span>");
            sb.append("</td></tr>");
        }
        sb.append("</table></div>");
        return page("Stations", sb.toString());
    }

    static String bookPage(Station s) {
        String body = "<div class='card'><h2>Book: " + esc(s.name) + "</h2>"
            + "<p>" + esc(s.location) + " &bull; " + esc(s.type) + " &bull; &#8377;" + money(s.rate)
            + "/kWh &bull; " + s.slots + " slot(s) free</p>"
            + "<form method='post' action='/book'>"
            + "<input type='hidden' name='station' value='" + s.id + "'>"
            + "<label>Your Name</label><input name='user' required>"
            + "<label>Date</label><input type='date' name='date' required>"
            + "<label>Start Time</label><input type='time' name='time' required>"
            + "<label>Charging Duration (hours)</label>"
            + "<input type='number' name='hours' min='0.5' max='24' step='0.5' value='1' required>"
            + "<br><br><button class='btn' type='submit'>Confirm Booking</button> "
            + "<a class='btn gray' href='/'>Back</a></form></div>";
        return page("Book", body);
    }

    static String billPage(Booking b, String msg) {
        StringBuilder sb = new StringBuilder();
        if (msg != null && !msg.isEmpty()) sb.append("<div class='msg'>").append(esc(msg)).append("</div>");
        sb.append("<div class='card'><h2>Invoice #").append(b.id).append(" ")
          .append("<span class='badge ").append(b.status).append("'>").append(b.status).append("</span> ")
          .append("<span class='badge ").append(b.paid ? "paid'>PAID" : "pending'>PAYMENT PENDING").append("</span></h2>")
          .append("<p><b>Customer:</b> ").append(esc(b.user)).append("<br>")
          .append("<b>Station:</b> ").append(esc(b.station.name)).append(" (").append(esc(b.station.location)).append(")<br>")
          .append("<b>Charger:</b> ").append(esc(b.station.type)).append("<br>")
          .append("<b>Date / Time:</b> ").append(esc(b.date)).append(" ").append(esc(b.time))
          .append(" for ").append(b.hours).append(" hr</p>")
          .append("<table class='invoice'>")
          .append("<tr><td>Energy: ").append(money(b.kwh())).append(" kWh &times; &#8377;")
          .append(money(b.station.rate)).append("</td><td>&#8377;").append(money(b.energy())).append("</td></tr>")
          .append("<tr><td>GST (18%)</td><td>&#8377;").append(money(b.gst())).append("</td></tr>")
          .append("<tr class='total'><td>TOTAL</td><td>&#8377;").append(money(b.total())).append("</td></tr></table>")
          .append("<br><div class='actions'>");
        if (b.status.equals("BOOKED") && !b.paid) {
            sb.append("<form method='post' action='/pay'><input type='hidden' name='id' value='").append(b.id)
              .append("'><button class='btn' type='submit'>Pay &#8377;").append(money(b.total())).append("</button></form>")
              .append("<form method='post' action='/cancel' onsubmit=\"return confirm('Cancel this booking?')\">")
              .append("<input type='hidden' name='id' value='").append(b.id)
              .append("'><button class='btn red' type='submit'>Cancel Booking</button></form>");
        }
        sb.append("<button class='btn gray' onclick='window.print()'>Print</button> ")
          .append("<a class='btn gray' href='/bookings'>All Bookings</a></div></div>");
        return page("Invoice", sb.toString());
    }

    static String bookingsPage() {
        StringBuilder sb = new StringBuilder("<div class='card'><h2>All Bookings</h2>");
        if (bookings.isEmpty()) {
            sb.append("<p>No bookings yet. <a href='/'>Book a slot</a></p>");
        } else {
            sb.append("<table><tr><th>ID</th><th>User</th><th>Station</th><th>Date</th><th>Time</th>")
              .append("<th>Hrs</th><th>Total</th><th>Status</th><th>Payment</th><th></th></tr>");
            for (Booking b : bookings) {
                sb.append("<tr><td>").append(b.id).append("</td><td>").append(esc(b.user)).append("</td><td>")
                  .append(esc(b.station.name)).append("</td><td>").append(esc(b.date)).append("</td><td>")
                  .append(esc(b.time)).append("</td><td>").append(b.hours).append("</td><td>&#8377;")
                  .append(money(b.total())).append("</td><td><span class='badge ").append(b.status).append("'>")
                  .append(b.status).append("</span></td><td>").append(b.paid ? "Paid" : "Pending")
                  .append("</td><td><a class='btn' href='/bill?id=").append(b.id).append("'>View</a></td></tr>");
            }
            sb.append("</table>");
        }
        sb.append("</div>");
        return page("Bookings", sb.toString());
    }

    // ---------- Request Handler ----------
    static void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            boolean post = ex.getRequestMethod().equalsIgnoreCase("POST");
            Map<String, String> q = parse(ex.getRequestURI().getRawQuery());
            Map<String, String> f = post
                ? parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                : new HashMap<>();

            synchronized (LOCK) {
                switch (path) {
                    case "/":
                        send(ex, 200, homePage(q.get("msg")));
                        break;

                    case "/book":
                        if (!post) {
                            Station s = findStation(toInt(q.getOrDefault("id", "")));
                            if (s == null) { redirect(ex, flash("Invalid station.")); break; }
                            if (s.slots <= 0) { redirect(ex, flash("Sorry, no slots available at " + s.name + ".")); break; }
                            send(ex, 200, bookPage(s));
                        } else {
                            Station s = findStation(toInt(f.getOrDefault("station", "")));
                            String user = f.getOrDefault("user", "").trim();
                            String date = f.getOrDefault("date", "").trim();
                            String time = f.getOrDefault("time", "").trim();
                            double hours = toDouble(f.getOrDefault("hours", ""));
                            if (s == null || user.isEmpty() || date.isEmpty() || time.isEmpty() || hours <= 0 || hours > 24) {
                                redirect(ex, flash("Invalid booking details. Please try again."));
                                break;
                            }
                            if (s.slots <= 0) { redirect(ex, flash("Sorry, no slots available.")); break; }
                            Booking b = new Booking(user, s, date, time, hours);
                            bookings.add(b);
                            s.slots--;
                            redirect(ex, "/bill?id=" + b.id + "&msg=" +
                                URLEncoder.encode("Booking confirmed! Your Booking ID is " + b.id, StandardCharsets.UTF_8));
                        }
                        break;

                    case "/bill": {
                        Booking b = findBooking(toInt(q.getOrDefault("id", "")));
                        if (b == null) redirect(ex, flash("Booking not found."));
                        else send(ex, 200, billPage(b, q.get("msg")));
                        break;
                    }

                    case "/pay": {
                        Booking b = findBooking(toInt(f.getOrDefault("id", "")));
                        if (!post || b == null) { redirect(ex, flash("Booking not found.")); break; }
                        String m;
                        if (b.status.equals("CANCELLED")) m = "Booking is cancelled.";
                        else if (b.paid) m = "Already paid.";
                        else { b.paid = true; b.status = "COMPLETED"; m = "Payment successful!"; }
                        redirect(ex, "/bill?id=" + b.id + "&msg=" + URLEncoder.encode(m, StandardCharsets.UTF_8));
                        break;
                    }

                    case "/cancel": {
                        Booking b = findBooking(toInt(f.getOrDefault("id", "")));
                        if (!post || b == null) { redirect(ex, flash("Booking not found.")); break; }
                        String m;
                        if (!b.status.equals("BOOKED")) m = "Cannot cancel: booking is already " + b.status + ".";
                        else if (b.paid) m = "Paid bookings cannot be cancelled.";
                        else { b.status = "CANCELLED"; b.station.slots++; m = "Booking cancelled. Slot released."; }
                        redirect(ex, "/bill?id=" + b.id + "&msg=" + URLEncoder.encode(m, StandardCharsets.UTF_8));
                        break;
                    }

                    case "/bookings":
                        send(ex, 200, bookingsPage());
                        break;

                    default:
                        send(ex, 404, page("Not Found", "<div class='card'><h2>404 - Page not found</h2><a href='/'>Go home</a></div>"));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, page("Error", "<div class='card'><h2>Something went wrong</h2><p>" + esc(String.valueOf(e)) + "</p></div>"));
        }
    }

    // ---------- Main ----------
    public static void main(String[] args) throws IOException {
        loadStations();
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(PORT), 0);
        } catch (java.net.BindException e) {
            System.out.println("Port " + PORT + " is already in use. Close the previous run (Ctrl+C in the terminal) and try again.");
            return;
        }
        server.createContext("/", EVChargingSystem::handle);
        server.start();

        String url = "http://localhost:" + PORT;
        System.out.println("=======================================");
        System.out.println(" EV Charging System is running!");
        System.out.println(" Ctrl+Click this link to open: " + url);
        System.out.println(" Press Ctrl+C to stop the server.");
        System.out.println("=======================================");

        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                Desktop.getDesktop().browse(new URI(url));
        } catch (Exception ignored) { /* link in terminal still works */ }
    }
}