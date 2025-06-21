# **EACL**: Enterprise Access ControL

EACL is a [SpiceDB-compatible](https://authzed.com/spicedb)* [ReBAC](https://en.wikipedia.org/wiki/Relationship-based_access_control) Authorization system built in Clojure and backed by Datomic, used at [CloudAfrica](https://cloudafrica.net/).

## Authentication vs Authorization

- Authentication or **AuthN** means, "Who are you?"
- Authorization or **AuthZ** means "What can `<user>` do?"

EACL is an embedded Authorization system primarily concerned with efficiently answering "What can `<user>` do?"

'Embedded' here means EACL is situated next to your data, requiring one less external system to sync with.

## Problem Statement

How can we efficiently answer the following permission-related questions without a network hop?

1. **Check Permission:** "Does `<subject>` have `<permission>` on `<resource>`?"
2. **Enumerate Subjects:** "Which `<subjects>` have `<permission>` on `<resource>`?"
3. **Enumerate Resources:** "Which `<resources>` does `<subject>` have `<permission>` for?"

## Why EACL?

1. Avoids network I/O to an external AuthZ system, which can outperform SpiceDB at small to medium-scale.
2. Fully consistent queries until you need `ZedTokens`, which still require a DB query or a cache. Caching is hard.
3. Enables 1-for-1 `Relationship` syncing to an external ReBAC system like SpiceDB in near real-time without complex diffing once you need consistency semantics.

## ReBAC

In a ReBAC system like EACL, _Subjects_ & _Resources_ are related via _Relationships_. A `Relationship` is just:
- `[subject relation resource]`,
  - e.g. `[user1 :owner account1]`, i.e. `user-1` owns `account1`.
- and `[account :account product]`,
  - e.g. `[account1 :account product1]`, i.e. `product1` falls under `account1`.

Therefore, we may want all users who own `account-1` should be able to `:view` or `:edit` products under that account, and these permissions are inferred from some permission schema.  

A ReBAC system like EACL answers these permission questions without additional network I/O to an external authorization system.


## Usage

The `IAuthorization` protocol in [src/eacl/core.clj](src/eacl/core.clj) defines an idiomatic Clojure interface to the [SpiceDB gRPC API](https://buf.build/authzed/api/docs/main:authzed.api.v1). We have an implementation for the gRPC API that is not open-sourced yet, but will be open-sourced.

The primary API call is `can?`:

```clojure
(can? db subject permission resource) => true | false
```

To maintain Spice-compatibility, all Spice objects (subjects or resources) require,
- `:eacl/type` (keyword), e.g. `:server` or `:account`
- `:eacl/id` (unique string), e.g. `"unique-account-1"`

You can construct a Spice Object using `eacl.core/spice-object` accepts `type`, `id` and optionally `subject_relation`. It returns a SpiceObject.

It is convenient to define partial helpers for your known object types, e.g.

```clojure
(def ->user (partial spice-object :user))
(def ->server (partial spice-object :server))
(def ->product (partial spice-object :product))
; etc.
```

Then you can construct Spice-compatible records for passing subjects & resources to `can?`:

E.g.
```clojure
(eacl/can? acl (->user "user1") :edit (->product "product1")) => true | false
```

(Todo better docs for `write-relationships`.)

## Configuration

```clojure
(ns my-project
  (:require [eacl.core :as eacl :refer [spice-object]]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.core :as eacl.datomic]))

(def datomic-uri "datomic:mem://eacl-test")
(d/create-database datomic-uri)
(def conn (d/connect datomic-uri))

; Transact the EACL Datomic Attributes
@(d/transact conn schema/v4-schema) ; transact EACL schema attributes

; Transact your Spice-compatible EACL Schema (details below):
@(d/transact conn your-eacl-schema)

; Make an EACL Datomic client that satisfies the `IAuthorization` protocol:
(def acl (eacl.datomic/make-client conn))

; Ensure your entities have `:eacl/type` & `:eacl/id` attributes:
@(d/transact conn
   [{:eacl/type :user
     :eacl/id   "user1"}
  
    {:eacl/type :server
     :eacl/id   "server1"}])
; These correspond to Spice type + id and populate SpiceObject. 
; - `:eacl/type` is a keyword, e.g. :account
; - `:eacl/id` is a unique string ident to retain compatibility with SpiceDB, which uses strings.

; Transact EACL Relationships (schema is detailed below):
@(d/transact conn your-relationships)

; Now you can do `can?` permission checks:
(eacl/can? acl (->user "user1") :view (->server "server1"))
=> true | false

(eacl/can! acl (->user "user1" :view (->server "server1"))
=> true or throws (if `can?` returned false)

; Given a subject, you can enumerate the resources they have a permission for using
; cursor-based pagination: 
(eacl/lookup-resources acl {:subject       (->user "user1")
                            :permission    :view
                            :resource/type :server
                            :limit         1000
                            :cursor        'cursor}) ; nil for 1st page
=> {:data [{:type :server :id "server1"},
           {:type :server :id "server2"}
           ...]
    :cursor 'next-cursor}
; ^ collection of :server resources (spice-object) that subject user1 can :view.

; "Which subjects can :view (->server "server-1")?
; lookup-subjects supprots limit & offset pending cursor-based pagination:
(eacl/lookup-subjects acl {:resource      (->server "server1")
                           :permission    :view
                           :resource/type :server
                           :limit         1000
                           :offset        0}) ; cursor pagination WIP for lookup-subjects.
; ; collection of subjects that can :view the :server resource "server1".
=> {:data [{:type :user,    :id "user1"},
           {:type :account, :id "account1"} ; notice how EACL returns subjects of multiple types 
           ...]
    :cursor nil} ; WIP cursor-based pagination for lookup-subjects (coming soon).
```
## Schema

EACL schema lives in Datomic. The following functions correspond to SpiceDB schema and return Datomic entity maps:

- `(Relation resource-type relation-name subject-type)`
- `(Permission resource-type relation-name permission)`
- `(Relationship user1 relation-name server1)` confers `permission` to subject `user1` on server1.

Additionally, we support SpiceDB arrow syntax with a 4-arity call to Permission:
- `(Permission :resource-type :relation_name :relation_permission :admin)`

e.g.

```
(Permission :server :account :owner :admin)
```

Which you can read as follows:

```
definition account {
  relation owner: user
  permission admin = owner
}

definition server {
  relation account: account
  permission admin = account->admin # <-- 4 arity arrow syntax
}

```

## Example Schema Translation

Given the following SpiceDB schema,

```
definition user {
}

definition platform {
  relation super_admin: user
  pemission admin = super_admin
}

definition account {
  relation owner: user
  relation platform: platform
  permission admin = owner + platform->admin
}

definition server {
  relation account: account
  relation shared_admin: user
  
  permission reboot = shared_admin + account->admin
}
```

How to model this in EACL?

```clojure
(require '[datomic.api :as d])
(require '[eacl.datomic.impl :as spiceomic :refer [Relation Permission Relationship]])

@(d/transact conn
             [(Relation :platform :super_admin :user)
              (Permission :platform :super_admin :admin)

              (Relation :account :super_admin :user)
              (Relation :account :platform :platform)

              (Permission :account :owner :admin)
              (Permission :account :owner :admin)
              (Permission :platform :super_admin :admin :admin)

              (Relation :server :account :account)
              (Relation :server :shared_admin :user)

              (Permission :server :shared_admin :reboot)
              (Permission :server :account :admin :reboot)])
```

Now you can transact relationships:

```clojure
@(d/transact conn
  [{:db/id     "user1-tempid"
    :eacl/type :user
    :eacl/id   "user1"}

   {:db/id     "account1-tempid"
    :eacl/type :account
    :eacl/id   "account1"}

   (Relationship "user1-tempid" :owner "account1-tempid")])
```

(I'm using tempids in example because entities are defined in same tx as relationships)

## Limitations, Deficiencies & Gotchas:
- EACL makes no performance-related claims about being fast. It should be fine for <10k entities.
- Arrow syntax requires an explicit permission on the related resource.
- Only "sum" permissions are supported, not negation, i.e. `permission admin = owner + shared_admin` is supported, but not `permission admin = owner - shared_member`. 
- Specify a `Permission` for each relation in a sum-type permission.
- EACL is fully consistent so does not support the SpiceDB consistency semantics enabled by ZedTokens or Zookies. However, you can simulate this by using an older cached `db` value.
  SpiceDB is heavily optimised to maintain a consistent cache.
- `subject.relation` is not currently supported, but can be added.

## How to Run All Tests

```shell
clj -X:test
```
## Run Test for One Namespace

```bash
clj -M:test -n eacl.datomic.impl-test
```

## Run Tests for Multiple Namespaces

```bash
clj -X:test :nses '["my-namespace1" "my-namespace2"]'
```

Note difference between `-M` & `-X` switches.

## Run a single test (under deftest)

```bash
clojure -M:test -v my.namespace/test-name
```

EACL is a Datomic-based authorization system based on .

- There are Subjects & Resources.
- Resources have Relations, e.g. `:product/owner`, which confers a set of permissions on a Subject, e.g. 
  `:product/view`, `:product/delete`.
- Subjects & Resources are connected via Relationships.
- Relationships have `{:keys [subject relation resource]}`, e.g. `(Relationship :user/joe :product/owner :test/product1)`.

If a Resource is reachable by a Subject via a Relation, the permissions from that Relation are conferred on the subject.

Todo better docs, but there's also:

- (lookup-subjects ...)
- (lookup-resources ...)

## Funding

This open-source work was generously funded by my employer, [CloudAfrica](https://cloudafrica.net/), a Clojure shop. We occasionally hire Clojure & Datomic experts.

# Licence

- EACL switched from BSL to an Affero GPL licence on 2025-05-27.
- However, we are considering re-licensing under a more permissive open-source license.