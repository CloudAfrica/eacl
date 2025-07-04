(ns eacl.datomic.fixtures
  (:require [datomic.api :as d]
            [eacl.core :refer [spice-object]]
            [eacl.datomic.impl :as eacl :refer (Relation Relationship Permission)]))

; These are helpers specific to CA (todo move out):
(def ->user (partial spice-object :user))
(def ->team (partial spice-object :team))
(def ->server (partial spice-object :server))
(def ->platform (partial spice-object :platform))
(def ->account (partial spice-object :account))
(def ->vpc (partial spice-object :vpc))
(def ->backup (partial spice-object :backup))
(def ->host (partial spice-object :host))

(def base-fixtures
  [; Schema
   (Relation :platform :super_admin :user)                  ; means resource-type/relation subject-type, e.g. definition platform { relation super_admin: user }.
   (Permission :platform :super_admin :platform_admin)      ; hack to support platform->admin
   ; definition platform {
   ;   relation super_admin: user;
   ;   permission platform_admin = super_admin   # EACL requires this hack for arrow relations because we traverse permissions->relations. Could be fixed.
   ; }

   (Relation :vpc/account :account)                         ; vpc, relation account: account.
   ;permission admin = account->admin + shared_admin
   (Permission :vpc :shared_admin :admin)
   (Permission :vpc :account :admin :admin)                 ; vpc/admin = account->admin (arrow syntax)

   ; VPCs:
   (Relation :vpc/owner :user)
   (Permission :vpc/owner :admin)                           ; just admin?

   ; Accounts:
   (Relation :account :owner :user)                         ; Account has an owner (a user)
   (Relation :account :platform :platform)

   (Permission :account :owner :admin)                      ; Owner of account gets admin on account
   (Permission :account :owner :view)
   (Permission :account :platform :platform_admin :admin)   ; hack for platform->super_admin.
   (Permission :account :platform :platform_admin :view)    ; spurious.

   ; Teams:
   (Relation :team/account :account)

   ;; Servers:
   (Relation :server/account :account)

   (Permission :server/account :view)

   (Permission :server :account :admin :view)
   (Permission :server :account :admin :delete)
   (Permission :server :account :admin :reboot)
   ;(Permission :server :account :admin :reboot)

   (Permission :server/account :edit)
   ; Server Shared Admin:
   (Permission :server/shared_admin :view)
   (Permission :server/shared_admin :reboot)
   (Permission :server/shared_admin :admin)
   (Permission :server/shared_admin :delete)

   ;(Relation :server/company :company)
   ; can we combine these into one with multi-cardinality?

   ; these can go away
   (Relation :server/owner :user)

   (Permission :server/owner :view)
   ;(Permission :server/owner :reboot)
   (Permission :server/owner :edit)
   (Permission :server/owner :delete)

   ; Global Platform for Super Admins:
   {:db/id     "platform"
    :db/ident  :test/platform
    :eacl/type :platform
    :eacl/id   "platform"}

   ; Users:
   {:db/id     "user-1"
    :db/ident  :test/user1
    :eacl/type :user
    :eacl/id   "user-1"}

   ; we need to specify types for indices, but id can be tempid here.
   (Relationship (->user "user-1") :member (->team "team-1")) ; User 1 is on Team 1
   (Relationship (->user "user-1") :owner (->account "account-1"))

   ; Super User can do all the things:
   {:db/id     "super-user"
    :db/ident  :user/super-user
    :eacl/type :user
    :eacl/id   "super-user"}

   (Relationship (->user "super-user") :super_admin (->platform "platform"))

   {:db/id     "user-2"
    :db/ident  :test/user2
    :eacl/type :user
    :eacl/id   "user-2"}

   (Relationship (->user "user-2") :owner (->account "account-2"))

   ; Accounts
   {:db/id     "account-1"
    :db/ident  :test/account1
    :eacl/type :account
    :eacl/id   "account-1"}

   (Relationship (->platform "platform") :platform (->account "account-1"))

   {:db/id     "account-2"
    :db/ident  :test/account2
    :eacl/type :account
    :eacl/id   "account-2"}

   (Relationship (->platform "platform") :platform (->account "account-2"))

   ; VPC
   {:db/id     "vpc-1"
    :db/ident  :test/vpc
    :eacl/type :vpc
    :eacl/id   "vpc-1"}

   (Relationship (->account "account-1") :account (->vpc "vpc-1"))

   {:db/id     "vpc-2"
    :db/ident  :test/vpc2
    :eacl/type :vpc
    :eacl/id   "vpc-2"}

   (Relationship (->account "account-2") :account (->vpc "vpc-2"))

   ; Team
   {:db/id     "team-1"
    :db/ident  :test/team
    :eacl/type :team
    :eacl/id   "team-1"}

   ; Teams belongs to accounts
   (Relationship (->account "account-1") :account (->team "team-1"))

   {:db/id     "team-2"
    :db/ident  :test/team2
    :eacl/type :team
    :eacl/id   "team-2"}

   (Relationship (->account "account-2") :account (->team "team-2"))

   ;; Servers:
   {:db/id     "account1-server1"
    :db/ident  :test/server1
    :eacl/type :server
    :eacl/id   "account1-server1"}

   (Relationship (->account "account-1") :account (->server "account1-server1")) ; hmm let's check schema plz.

   {:db/id     "account1-server2"
    :eacl/type :server
    :eacl/id   "account1-server2"}

   (Relationship (->account "account-1") :account (->server "account1-server2"))

   {:db/id     "account2-server1"
    :db/ident  :test/server2
    :eacl/type :server
    :eacl/id   "account2-server1"}

   (Relationship (->account "account-2") :account (->server "account2-server1"))])

;; Team Membership:

;(Relationship "user-2" :team/member "team-2")



