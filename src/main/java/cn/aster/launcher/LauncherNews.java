package cn.aster.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Public official announcements; account credentials never pass through the website. */
final class LauncherNews {
    static final URI INDEX = URI.create("https://asterrpg.org/forum/news/");
    private static final URI FEED = URI.create("https://asterrpg.org/api/launcher/news/");

    record Item(String title, String summary, String date, URI url) {}

    static List<Item> load() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(FEED).timeout(Duration.ofSeconds(8)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new java.io.IOException("官网公告暂不可用");
        JsonArray items = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("items");
        List<Item> result = new ArrayList<>();
        for (var element : items) {
            JsonObject item = element.getAsJsonObject();
            URI url = INDEX.resolve(item.get("url").getAsString());
            if (!"https".equals(url.getScheme()) || !"asterrpg.org".equals(url.getHost())) continue;
            result.add(new Item(item.get("title").getAsString(), item.get("summary").getAsString(),
                    item.get("date").getAsString(), url));
        }
        return List.copyOf(result);
    }
}
