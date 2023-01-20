/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2021-2022 Artipie
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.artipie.conan.http;

import com.artipie.asto.Storage;
import com.artipie.conan.ItemTokenizer;
import com.artipie.http.Headers;
import com.artipie.http.Slice;
import com.artipie.http.auth.Action;
import com.artipie.http.auth.Authentication;
import com.artipie.http.auth.BearerAuthSlice;
import com.artipie.http.auth.Permission;
import com.artipie.http.auth.Permissions;
import com.artipie.http.auth.TokenAuthentication;
import com.artipie.http.auth.Tokens;
import com.artipie.http.rq.RqMethod;
import com.artipie.http.rs.RsStatus;
import com.artipie.http.rs.RsWithHeaders;
import com.artipie.http.rs.RsWithStatus;
import com.artipie.http.rt.ByMethodsRule;
import com.artipie.http.rt.RtRule;
import com.artipie.http.rt.RtRulePath;
import com.artipie.http.rt.SliceRoute;
import com.artipie.http.slice.SliceDownload;
import com.artipie.http.slice.SliceSimple;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Artipie {@link Slice} for Conan repository HTTP API.
 * @since 0.1
 * @checkstyle ClassDataAbstractionCouplingCheck (500 lines)
 * @checkstyle ClassFanOutComplexityCheck (500 lines)
 */
public final class ConanSlice extends Slice.Wrap {

    /**
     * Anonymous tokens.
     */
    private static final Tokens ANONYMOUS = new Tokens() {

        @Override
        public TokenAuthentication auth() {
            return token ->
                CompletableFuture.completedFuture(
                    Optional.of(new Authentication.User("anonymous"))
                );
        }

        @Override
        public String generate(final Authentication.User user) {
            return "123qwe";
        }
    };

    /**
     * Fake implementation of {@link Tokens} for the single user.
     * @since 0.5
     */
    public static final class FakeAuthTokens implements Tokens {

        /**
         * Token value for the user.
         */
        private final String token;

        /**
         * Username value for the user.
         */
        private final String username;

        /**
         * Ctor.
         * @param token Token value for the user.
         * @param username Username value for the user.
         */
        public FakeAuthTokens(final String token, final String username) {
            this.token = token;
            this.username = username;
        }

        @Override
        public TokenAuthentication auth() {
            return tkn -> {
                Optional<Authentication.User> res = Optional.empty();
                if (this.token.equals(tkn)) {
                    res = Optional.of(new Authentication.User(this.username));
                }
                return CompletableFuture.completedFuture(res);
            };
        }

        @Override
        public String generate(final Authentication.User user) {
            if (user.name().equals(this.username)) {
                return this.token;
            }
            throw new IllegalStateException(String.join("Unexpected user: ", user.name()));
        }
    }

    /**
     * Ctor.
     * @param storage Storage object.
     * @param tokenizer Tokenizer for repository items.
     */
    public ConanSlice(final Storage storage, final ItemTokenizer tokenizer) {
        this(storage, Permissions.FREE, Authentication.ANONYMOUS, ConanSlice.ANONYMOUS, tokenizer);
    }

    /**
     * Ctor.
     * @param storage Storage object.
     * @param perms Permissions.
     * @param auth Authentication parameters.
     * @param tokens User auth token generator.
     * @param tokenizer Tokens provider for repository items.
     * @checkstyle MethodLengthCheck (200 lines)
     * @checkstyle ParameterNumberCheck (20 lines)
     */
    @SuppressWarnings("PMD.ExcessiveMethodLength")
    public ConanSlice(
        final Storage storage,
        final Permissions perms,
        final Authentication auth,
        final Tokens tokens,
        final ItemTokenizer tokenizer
    ) {
        super(
            new SliceRoute(
                new RtRulePath(
                    new RtRule.ByPath("^/v1/ping$"),
                    new SliceSimple(
                        new RsWithHeaders(
                            new RsWithStatus(RsStatus.ACCEPTED),
                            new Headers.From(
                                "X-Conan-Server-Capabilities",
                                "complex_search,revisions,revisions"
                            )
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(new PathWrap.CredsCheck().getPath()),
                        ByMethodsRule.Standard.GET
                    ),
                    new BearerAuthSlice(
                        new UsersEntity.CredsCheck(),
                        tokens.auth(),
                        new Permission.ByName(perms, Action.Standard.READ)
                    )
                ),
                new RtRulePath(
                    new RtRule.ByPath(new PathWrap.UserAuth().getPath()),
                    new UsersEntity.UserAuth(auth, tokens)
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(new PathWrap.DigestForPkgBin().getPath()),
                        ByMethodsRule.Standard.GET
                    ),
                    new BearerAuthSlice(
                        new ConansEntity.DigestForPkgBin(storage),
                        tokens.auth(),
                        new Permission.ByName(perms, Action.Standard.READ)
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(new PathWrap.DigestForPkgSrc().getPath()),
                        ByMethodsRule.Standard.GET
                    ),
                    new BearerAuthSlice(
                        new ConansEntity.DigestForPkgSrc(storage),
                        tokens.auth(),
                        new Permission.ByName(perms, Action.Standard.READ)
                    )
                ),
                new RtRulePath(
                    new RtRule.ByPath(new PathWrap.SearchSrcPkg().getPath()),
                    new BearerAuthSlice(
                        new ConansEntity.GetSearchSrcPkg(storage),
                        tokens.auth(),
                        new Permission.ByName(perms, Action.Standard.READ)
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(new PathWrap.DownloadBin().getPath()),
                        ByMethodsRule.Standard.GET
                    ),
                    new BearerAuthSlice(
                        new ConansEntity.DownloadBin(storage),
                        tokens.auth(),
                        new Permission.ByName(perms, Action.Standard.READ)
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(new PathWrap.DownloadSrc().getPath()),
                        ByMethodsRule.Standard.GET
                    ),
                    new BearerAuthSlice(
                        new ConansEntity.DownloadSrc(storage),
                        tokens.auth(),
                        new Permission.ByName(perms, Action.Standard.READ)
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(new PathWrap.SearchBinPkg().getPath()),
                        ByMethodsRule.Standard.GET
                    ),
                    new BearerAuthSlice(
                        new ConansEntity.GetSearchBinPkg(storage),
                        tokens.auth(),
                        new Permission.ByName(perms, Action.Standard.READ)
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(new PathWrap.PkgBinInfo().getPath()),
                        ByMethodsRule.Standard.GET
                    ),
                    new BearerAuthSlice(
                        new ConansEntity.GetPkgInfo(storage),
                        tokens.auth(),
                        new Permission.ByName(perms, Action.Standard.READ)
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(new PathWrap.PkgBinLatest().getPath()),
                        ByMethodsRule.Standard.GET
                    ),
                    new BearerAuthSlice(
                        new ConansEntityV2.PkgBinLatest(storage),
                        tokens.auth(),
                        new Permission.ByName(perms, Action.Standard.READ)
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(new PathWrap.PkgSrcLatest().getPath()),
                        ByMethodsRule.Standard.GET
                    ),
                    new BearerAuthSlice(
                        new ConansEntityV2.PkgSrcLatest(storage),
                        tokens.auth(),
                        new Permission.ByName(perms, Action.Standard.READ)
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(new PathWrap.PkgBinFile().getPath()),
                        ByMethodsRule.Standard.GET
                    ),
                    new BearerAuthSlice(
                        new ConansEntityV2.PkgBinFile(storage),
                        tokens.auth(),
                        new Permission.ByName(perms, Action.Standard.READ)
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(new PathWrap.PkgBinFiles().getPath()),
                        ByMethodsRule.Standard.GET
                    ),
                    new BearerAuthSlice(
                        new ConansEntityV2.PkgBinFiles(storage),
                        tokens.auth(),
                        new Permission.ByName(perms, Action.Standard.READ)
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(new PathWrap.PkgSrcFile().getPath()),
                        ByMethodsRule.Standard.GET
                    ),
                    new BearerAuthSlice(
                        new ConansEntityV2.PkgSrcFile(storage),
                        tokens.auth(),
                        new Permission.ByName(perms, Action.Standard.READ)
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(new PathWrap.PkgSrcFiles().getPath()),
                        ByMethodsRule.Standard.GET
                    ),
                    new BearerAuthSlice(
                        new ConansEntityV2.PkgSrcFiles(storage),
                        tokens.auth(),
                        new Permission.ByName(perms, Action.Standard.READ)
                    )
                ),
                new RtRulePath(
                    new ByMethodsRule(RqMethod.GET),
                    new BearerAuthSlice(
                        new SliceDownload(storage),
                        tokens.auth(),
                        new Permission.ByName(perms, Action.Standard.READ)
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(ConanUpload.UPLOAD_SRC_PATH.getPath()),
                        ByMethodsRule.Standard.POST
                    ),
                    new BearerAuthSlice(
                        new ConanUpload.UploadUrls(storage, tokenizer),
                        tokens.auth(),
                        new Permission.ByName(perms, Action.Standard.WRITE)
                    )
                ),
                new RtRulePath(
                    new ByMethodsRule(RqMethod.PUT),
                    new ConanUpload.PutFile(storage, tokenizer)
                )
            )
        );
    }
}
