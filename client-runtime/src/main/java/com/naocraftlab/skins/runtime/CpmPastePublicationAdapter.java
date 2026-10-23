package com.naocraftlab.skins.runtime;

import com.google.gson.*;
import com.google.gson.stream.*;
import com.naocraftlab.skins.client.CpmModelPublication;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.GameSessionTokenUnavailableException;
import com.naocraftlab.skins.core.api.PublicHttpsImageFetcher;
import java.io.*;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class CpmPastePublicationAdapter implements CpmModelPublication {
    @FunctionalInterface public interface Exchange {
        PublicHttpsImageFetcher.Response send(String method, URI uri, Map<String,String> headers,
                byte[] body, Duration timeout, int maximum) throws IOException;
        default PublicHttpsImageFetcher.Response send(String method, URI uri, Map<String,String> headers,
                byte[] body, Duration timeout, int maximum, BooleanSupplier beforeSend) throws IOException {
            if (!beforeSend.getAsBoolean()) throw new PublicHttpsImageFetcher.FixedRequestException(false);
            if (beforeSend instanceof PublicHttpsImageFetcher.FixedRequestScope scope && !scope.beforeSend())
                throw new PublicHttpsImageFetcher.FixedRequestException(false);
            return send(method, uri, headers, body, timeout, maximum);
        }
    }
    @FunctionalInterface public interface Join {
        int send(UUID profile, String token, String serverId, Duration timeout) throws IOException;
        default int send(UUID profile, String token, String serverId, Duration timeout,
                BooleanSupplier beforeSend) throws IOException {
            if (!beforeSend.getAsBoolean()) throw new PublicHttpsImageFetcher.FixedRequestException(false);
            return send(profile, token, serverId, timeout);
        }
    }

    public static Exchange fixedExchange(PublicHttpsImageFetcher transport) {
        return new Exchange() {
            public PublicHttpsImageFetcher.Response send(String method, URI uri, Map<String,String> headers,
                    byte[] body, Duration timeout, int maximum) throws IOException {
                return transport.fixedRequest(method, uri, headers, body, timeout, maximum);
            }
            public PublicHttpsImageFetcher.Response send(String method, URI uri, Map<String,String> headers,
                    byte[] body, Duration timeout, int maximum, BooleanSupplier beforeSend) throws IOException {
                return transport.fixedRequest(method, uri, headers, body, timeout, maximum, beforeSend);
            }
        };
    }
    private final GameSessionTokenSource sessions;
    private final Exchange exchange;
    private final Join join;
    private final LongSupplier time;
    public CpmPastePublicationAdapter(GameSessionTokenSource sessions, Exchange exchange, Join join, LongSupplier time) {
        this.sessions=Objects.requireNonNull(sessions);this.exchange=Objects.requireNonNull(exchange);
        this.join=Objects.requireNonNull(join);this.time=Objects.requireNonNull(time);
    }
    @Override public Result publish(Request request, BooleanSupplier current) {
        long started=time.getAsLong();
        try {
            GameSessionTokenSource.SessionIdentity identity;
            try { identity=sessions.currentSession(); } catch (RuntimeException missing) { return Result.stopped(Reason.MISSING_SESSION); }
            if (identity==null || !identity.profileName().matches("[A-Za-z0-9_]{1,16}")) return Result.stopped(Reason.MISSING_SESSION);
            guard(current,identity,started);
            JsonObject connect=object(send("connect?name="+identity.profileName(),null,null,started,5,65536,current,identity));
            String id=string(connect,"id"),key=string(connect,"key");
            if (!id.matches("[!-~]{1,1024}") || key.length()>1024) throw new Stop(Reason.PROTOCOL);
            byte[] decoded;
            try { decoded=Base64.getDecoder().decode(key); } catch(IllegalArgumentException bad) { throw new Stop(Reason.PROTOCOL); }
            if(decoded.length==0 || decoded.length>1024 || !Base64.getEncoder().encodeToString(decoded).equals(key)) throw new Stop(Reason.PROTOCOL);
            MessageDigest digest;
            try { digest=MessageDigest.getInstance("SHA-1"); } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            digest.update("tom5454-paste".getBytes(StandardCharsets.US_ASCII));digest.update(id.getBytes(StandardCharsets.US_ASCII));
            String serverId=new BigInteger(digest.digest(decoded)).toString(16);
            guard(current,identity,started);
            int accepted;
            try {
                accepted=sessions.withSession((fresh,token)-> {
                    guard(current,identity,started);
                    if(!identity.equals(fresh)) throw new Stop(Reason.STALE);
                    if(token==null || token.isBlank()) throw new Stop(Reason.MISSING_SESSION);
                    return join.send(fresh.profileId(),token,serverId,remaining(started,30,current),
                            checkpoint(current,identity,started,false));
                });
            } catch(GameSessionTokenUnavailableException missing) { return Result.stopped(Reason.MISSING_SESSION); }
            if(accepted!=204) throw new Stop(accepted==401 || accepted==403 ? Reason.PROOF_REJECTED : Reason.PROTOCOL);
            guard(current,identity,started);
            PublicHttpsImageFetcher.Response session=send("session",id,null,started,30,65536,current,identity);
            if(session.status()==401 || session.status()==403) throw new Stop(Reason.PROOF_REJECTED);
            if(object(session).size()!=0) throw new Stop(Reason.PROTOCOL);
            guard(current,identity,started);
            JsonObject list=object(send("list",id,null,started,30,262144,current,identity));
            long maxSize=number(list,"maxSize"),maxFiles=number(list,"maxFiles");
            JsonElement files=list.get("files");
            if(files==null || !files.isJsonArray() || files.getAsJsonArray().size()>4096) throw new Stop(Reason.PROTOCOL);
            for(JsonElement file:files.getAsJsonArray()) {
                if(!file.isJsonObject()) throw new Stop(Reason.PROTOCOL);
                JsonObject entry=file.getAsJsonObject();string(entry,"id");string(entry,"name");string(entry,"time");
            }
            if(files.getAsJsonArray().size()>=maxFiles) throw new Stop(Reason.COUNT_QUOTA);
            byte[] body=Base64.getEncoder().encode(request.wire());
            if(body.length>maxSize) throw new Stop(Reason.SIZE_QUOTA);
            guard(current,identity,started);
            PublicHttpsImageFetcher.Response uploaded;
            try { uploaded=send("upload",id,body,started,30,65536,current,identity,request.name()); }
            catch(Stop stopped) { return stopped.sent ? new Result(Status.UNCERTAIN,stopped.reason,Optional.empty()) : Result.stopped(stopped.reason); }
            catch(PublicHttpsImageFetcher.FixedRequestException failure) {
                return failure.possibleSend() ? new Result(Status.UNCERTAIN,Reason.NETWORK,Optional.empty()) : Result.stopped(Reason.NETWORK);
            }
            catch(IOException uncertain) { return new Result(Status.UNCERTAIN,Reason.NETWORK,Optional.empty()); }
            try {
                if(uploaded.status()==413) throw new Stop(Reason.SIZE_QUOTA);
                String paste=string(object(uploaded),"id");
                if(!paste.matches("[A-Za-z0-9_-]{1,1024}")) throw new Stop(Reason.PROTOCOL);
                return new Result(Status.CONFIRMED,Reason.NONE,Optional.of(paste));
            } catch(Stop invalid) { return new Result(Status.UNCERTAIN,invalid.reason,Optional.empty()); }
            catch(RuntimeException invalid) { return new Result(Status.UNCERTAIN,Reason.PROTOCOL,Optional.empty()); }
        } catch(Stop stopped) { return Result.stopped(stopped.reason); }
        catch(IOException failure) { return Result.stopped(Reason.NETWORK); }
        catch(RuntimeException failure) { return Result.stopped(Reason.PROTOCOL); }
    }
    private void guard(BooleanSupplier current,GameSessionTokenSource.SessionIdentity identity,long started) throws Stop {
        if(time.getAsLong()-started>=Duration.ofSeconds(120).toNanos()) throw new Stop(Reason.EXPIRED);
        if(current instanceof CpmModelPublication.Control control && control.remainingNanos() <= 0) throw new Stop(Reason.EXPIRED);
        if(!current.getAsBoolean()) throw new Stop(Reason.STALE);
        try { if(!identity.equals(sessions.currentSession())) throw new Stop(Reason.STALE); }
        catch(RuntimeException unavailable) { throw new Stop(Reason.STALE); }
    }
    private Duration remaining(long started,int seconds,BooleanSupplier current) throws Stop {
        long remaining=Duration.ofSeconds(120).toNanos()-(time.getAsLong()-started);
        if(current instanceof CpmModelPublication.Control control) remaining=Math.min(remaining,control.remainingNanos());
        if(remaining<=0) throw new Stop(Reason.EXPIRED);
        return Duration.ofNanos(Math.min(remaining,Duration.ofSeconds(seconds).toNanos()));
    }
    private BooleanSupplier checkpoint(BooleanSupplier current,GameSessionTokenSource.SessionIdentity identity,long started,boolean upload) {
        return new PublicHttpsImageFetcher.FixedRequestScope() {
            public boolean getAsBoolean() {
                try { guard(current,identity,started); return true; }
                catch (Stop stopped) { return false; }
            }
            public boolean beforeSend() {
                return getAsBoolean() && (!upload || !(current instanceof CpmModelPublication.Control control) || control.beginSend());
            }
        };
    }

    private PublicHttpsImageFetcher.Response send(String path,String id,byte[] body,long started,int seconds,int maximum,
            BooleanSupplier current,GameSessionTokenSource.SessionIdentity identity) throws IOException,Stop {
        return send(path,id,body,started,seconds,maximum,current,identity,null);
    }
    private PublicHttpsImageFetcher.Response send(String path,String id,byte[] body,long started,int seconds,int maximum,
            BooleanSupplier current,GameSessionTokenSource.SessionIdentity identity,String name) throws IOException,Stop {
        Map<String,String> headers=new LinkedHashMap<>();headers.put("Accept","application/json");
        if(id!=null) headers.put("Session",id);
        if(body!=null) { headers.put("File-Name",name);headers.put("Content-Length",Integer.toString(body.length));headers.put("Content-Type","text/plain"); }
        PublicHttpsImageFetcher.Response response=exchange.send(body==null?"GET":"POST",URI.create("https://paste.tom5454.com/api/"+path),Map.copyOf(headers),body==null?new byte[0]:body,remaining(started,seconds,current),maximum,
                checkpoint(current,identity,started,body!=null));
        if(response.bytes().length>maximum) throw new Stop(Reason.PROTOCOL,body!=null);
        for(String header:response.headers().keySet()) if(header.equalsIgnoreCase("set-cookie") || header.equalsIgnoreCase("location")) throw new Stop(Reason.PROTOCOL,body!=null);
        return response;
    }
    private static JsonObject object(PublicHttpsImageFetcher.Response response) throws Stop {
        if(response.status()==401 || response.status()==403) throw new Stop(Reason.PROOF_REJECTED);
        if(response.status()<200 || response.status()>=300) throw new Stop(Reason.PROTOCOL);
        try(JsonReader reader=new JsonReader(new StringReader(new String(response.bytes(),StandardCharsets.UTF_8)))) {
            JsonElement result=read(reader,0);
            if(reader.peek()!=JsonToken.END_DOCUMENT || !result.isJsonObject()) throw new Stop(Reason.PROTOCOL);
            JsonObject object=result.getAsJsonObject();
            if(object.has("error")) {
                JsonElement message=object.get("errorMessage");
                boolean rejected=message!=null && message.isJsonPrimitive() && message.getAsJsonPrimitive().isString()
                        && message.getAsString().equals("error.paste.invalidSession");
                if(message!=null && message.isJsonPrimitive() && message.getAsJsonPrimitive().isString()
                        && message.getAsString().equals("error.paste.fileTooBig")
                        && object.get("error").isJsonPrimitive() && object.get("error").getAsJsonPrimitive().isString())
                    throw new Stop(Reason.SIZE_QUOTA);
                throw new Stop(rejected ? Reason.PROOF_REJECTED : Reason.PROTOCOL);
            }
            return object;
        } catch(Stop stopped) { throw stopped; }
        catch(IOException | IllegalStateException invalid) { throw new Stop(Reason.PROTOCOL); }
    }
    private static JsonElement read(JsonReader reader,int depth) throws IOException,Stop {
        if(depth>16) throw new Stop(Reason.PROTOCOL);
        return switch(reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object=new JsonObject();reader.beginObject();int count=0;
                while(reader.hasNext()) { String key=reader.nextName();if(key.length()>4096 || object.has(key) || ++count>4096) throw new Stop(Reason.PROTOCOL);object.add(key,read(reader,depth+1)); }
                reader.endObject();yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array=new JsonArray();reader.beginArray();while(reader.hasNext()) { if(array.size()>=4096) throw new Stop(Reason.PROTOCOL);array.add(read(reader,depth+1)); }reader.endArray();yield array;
            }
            case STRING,NUMBER -> { boolean number=reader.peek()==JsonToken.NUMBER;String value=reader.nextString();if(value.length()>4096) throw new Stop(Reason.PROTOCOL);yield number?new JsonPrimitive(new java.math.BigDecimal(value)):new JsonPrimitive(value); }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull();yield JsonNull.INSTANCE; }
            default -> throw new Stop(Reason.PROTOCOL);
        };
    }
    private static String string(JsonObject object,String key) throws Stop {
        JsonElement value=object.get(key);
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isEmpty()) throw new Stop(Reason.PROTOCOL);
        return value.getAsString();
    }
    private static long number(JsonObject object,String key) throws Stop {
        JsonElement value=object.get(key);
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new Stop(Reason.PROTOCOL);
        try { long n=value.getAsBigDecimal().longValueExact();if(n<0) throw new Stop(Reason.PROTOCOL);return n; }
        catch(ArithmeticException invalid) { throw new Stop(Reason.PROTOCOL); }
    }
    private static final class Stop extends IOException {
        private static final long serialVersionUID=1L;
        private final Reason reason;
        private final boolean sent;
        private Stop(Reason reason) { this(reason,false); }
        private Stop(Reason reason,boolean sent) { this.reason=reason;this.sent=sent; }
    }
}
