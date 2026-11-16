package org.quarkpress.it.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.core.UserId;
import org.quarkpress.multisite.NewSite;
import org.quarkpress.multisite.Sites;
import org.quarkpress.persistence.Options;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.TenantIds;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostMeta;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.runtime.PostRepository;
import org.quarkpress.revisions.AutosaveChanges;
import org.quarkpress.revisions.AutosaveResult;
import org.quarkpress.revisions.Revisions;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.Memberships;
import org.quarkpress.security.QuarkPressPrincipal;
import org.quarkpress.security.SystemIdentity;

/**
 * Test scaffolding, not part of any API (a reduced copy of the QuarkPress REST test application's resource, with its
 * route shapes): sets up what the browser flows need and the REST API does not serve (memberships, sites, posts by a
 * given user, autosaves, edit locks, post dates, options, permanent deletion) through the modules' Java services (post
 * dates straight in the post's row). Every call acts on the site the request is bound to (by its Host header and path)
 * and as the caller: {@code /it/...} on the main site, {@code /<site>/it/...} ({@link ItSiteSetupResource}) on a
 * subdirectory site. The calls that write as the system or name another user's data need {@code manage_options}.
 */
@Path("/it")
@Produces(MediaType.APPLICATION_JSON)
public class ItSetupResource {

    /** A role to give a user (by id) on the bound site. */
    public record MembershipRequest(long user, String role) {}

    /** A new site in a subdirectory of the bound site's domain, such as {@code /fv01/}. */
    public record SiteRequest(String path, String title) {}

    /** A post to create on the bound site, written by the caller; {@code content} and {@code parent} are optional. */
    public record PostRequest(String type, String status, String title, String content, Long parent) {}

    /** An autosave of a post by the caller. */
    public record AutosaveRequest(long post, String title, String content) {}

    /** The edit lock of a post: held by {@code user} since {@code age} seconds ago. */
    public record LockRequest(long user, int age) {}

    /**
     * A post's last-modified time to set ({@code at}, UTC, {@code 2026-10-18T10:00:00}); with {@code date} its
     * publication time too, when it has one.
     */
    public record Backdate(long id, String at, boolean date) {}

    /** The posts to backdate. */
    public record BackdateRequest(List<Backdate> posts) {}

    @Inject
    PostRepository postRows;

    @Inject
    SecurityIdentity identity;

    @Inject
    SiteContext site;

    @Inject
    Memberships memberships;

    @Inject
    Sites sites;

    @Inject
    Posts posts;

    @Inject
    PostMeta postMeta;

    @Inject
    Revisions revisions;

    @Inject
    Options options;

    @Inject
    Capabilities capabilities;

    @Inject
    SystemIdentity system;

    @Inject
    SiteScope siteScope;

    /** The caller's user id; no {@code user} for an anonymous caller. */
    @GET
    @Path("/me")
    public Map<String, Object> me() {
        Map<String, Object> me = new LinkedHashMap<>();
        QuarkPressPrincipal.of(identity)
                .ifPresent(principal -> me.put("user", principal.user().value()));
        return me;
    }

    /** Replaces the user's roles on the bound site with the given one; answers the roles. */
    @POST
    @Path("/memberships")
    @Consumes(MediaType.APPLICATION_JSON)
    public List<String> membership(MembershipRequest request) {
        return memberships
                .setRole(site.siteId(), UserId.of(request.user()), request.role())
                .roles();
    }

    /** Creates a site at {@code path} on the bound site's domain; answers its id. */
    @POST
    @Path("/sites")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> site(SiteRequest request) {
        SiteInfo created = sites.create(NewSite.of(site.site().domain(), request.path(), request.title()));
        return Map.of("id", created.id().value());
    }

    /** Creates a post on the bound site as the caller; answers its id. */
    @POST
    @Path("/posts")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> post(PostRequest request) {
        PostChanges.Builder changes = PostChanges.builder();
        if (request.type() != null) {
            changes.type(request.type());
        }
        if (request.status() != null) {
            changes.status(request.status());
        }
        if (request.title() != null) {
            changes.title(request.title());
        }
        if (request.content() != null) {
            changes.content(request.content());
        }
        if (request.parent() != null) {
            changes.parent(request.parent());
        }
        return Map.of("id", posts.create(changes.build()).id().value());
    }

    /** Deletes the post for good, for a caller with {@code manage_options}; answers whether it existed. */
    @DELETE
    @Path("/posts/{id}")
    public Map<String, Object> delete(@PathParam("id") long id) {
        requireManageOptions();
        return Map.of("deleted", posts.delete(PostId.of(id), true).isPresent());
    }

    /** Autosaves the post as the caller; answers the autosave's id (the post's own when its draft was updated). */
    @POST
    @Path("/autosaves")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> autosave(AutosaveRequest request) {
        AutosaveResult result = revisions.autosave(
                PostId.of(request.post()),
                AutosaveChanges.builder()
                        .title(request.title())
                        .content(request.content())
                        .build());
        long id = switch (result) {
            case AutosaveResult.Draft draft -> draft.post().id().value();
            case AutosaveResult.Stored stored -> stored.autosave().id().value();
        };
        return Map.of("id", id);
    }

    /**
     * Sets the post's edit lock to {@code "<now - age>:<user>"}, for a caller with {@code manage_options}; answers the
     * value.
     */
    @PUT
    @Path("/locks/{post}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> lock(@PathParam("post") long post, LockRequest request) {
        requireManageOptions();
        String value = (Instant.now().getEpochSecond() - request.age()) + ":" + request.user();
        asSystem(site.siteId(), () -> {
            postMeta.update(PostId.of(post), "_edit_lock", TextNode.valueOf(value));
            return null;
        });
        return Map.of("value", value);
    }

    /**
     * Sets the dates of posts of the bound site past the services, for a caller with {@code manage_options}: the row
     * alone changes (no version, no event, no revision); answers how many posts changed.
     */
    @PUT
    @Path("/backdate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Map<String, Object> backdate(BackdateRequest request) {
        requireManageOptions();
        int changed = 0;
        for (Backdate post : request.posts()) {
            Instant at = LocalDateTime.parse(post.at()).toInstant(ZoneOffset.UTC);
            changed += postRows.getEntityManager()
                    .createQuery("update PostEntity p set p.modifiedAt = :at where p.siteId = :site and p.id = :id")
                    .setParameter("at", at)
                    .setParameter("site", TenantIds.param(site.siteId()))
                    .setParameter("id", post.id())
                    .executeUpdate();
            if (post.date()) {
                postRows.getEntityManager()
                        .createQuery("update PostEntity p set p.publishedAt = :at where p.siteId = :site"
                                + " and p.id = :id and p.publishedAt is not null")
                        .setParameter("at", at)
                        .setParameter("site", TenantIds.param(site.siteId()))
                        .setParameter("id", post.id())
                        .executeUpdate();
            }
        }
        return Map.of("changed", changed);
    }

    /** Sets an option of the bound site, for a caller with {@code manage_options} there; answers whether it changed. */
    @PUT
    @Path("/options/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> option(@PathParam("name") String name, JsonNode value) {
        requireManageOptions();
        return Map.of("changed", options.set(name, value));
    }

    /** The calls that write as the system or name another user's data are for a caller with {@code manage_options}. */
    private void requireManageOptions() {
        if (!capabilities.can("manage_options")) {
            throw new ForbiddenException();
        }
    }

    /**
     * Runs {@code work} as the system in the scope of {@code siteId}, on a thread of its own (the system identity
     * refuses to run inside an HTTP request), and waits for it.
     */
    private <T> T asSystem(SiteId siteId, Callable<T> work) {
        FutureTask<T> task = new FutureTask<>(() -> siteScope.call(siteId, () -> system.call(work)));
        Thread.ofVirtual().start(task);
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        }
    }
}
