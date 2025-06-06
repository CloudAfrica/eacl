(ns eacl.datomic.impl-test
  (:require [clojure.test :as t :refer [deftest testing is]]
            [datomic.api :as d]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.fixtures :as fixtures :refer [->user ->server]]
            [eacl.core :as eacl :refer [spice-object]]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.impl :as spiceomic :refer [Relation Relationship Permission can? lookup-subjects lookup-resources]]))

(deftest eacl3-tests

  (testing "Permission helper"
    (is (= #:eacl.permission{:resource-type   :server
                             :permission-name :admin
                             :relation-name   :owner}
           (Permission :server :owner :admin)))
    (testing "permission admin Permission can infer resource type from namespaced relation keyword"
      (is (= #:eacl.permission{:resource-type   :server
                               :permission-name :admin
                               :relation-name   :owner}
             (Permission :server/owner :admin)))))

  (testing "fixtures"
    (with-mem-conn [conn schema/v4-schema]
      (is @(d/transact conn fixtures/base-fixtures))

      (let [db (d/db conn)]
        "super-user can view all servers"
        (is (= #{(spice-object :server "server-1")
                 (spice-object :server "server-2")}
               (set (lookup-resources db {:subject       (->user "super-user")
                                          :permission    :view
                                          :resource/type :server}))))

        ":test/user can :view and :reboot their server"

        (is (can? db :test/user1 :view :test/server1))
        (is (can? db :test/user1 :reboot :test/server1))

        "...but :test/user2 can't."
        (is (not (can? db :test/user2 :view :test/server1)))
        (is (not (can? db :test/user2 :reboot :test/server1)))

        ":test/user1 is admin of :test/vpc because they own account"
        (is (can? db :test/user1 :admin :test/vpc))

        "and so is super-user because he is super_admin of platform"
        (is (can? db :user/super-user :admin :test/vpc))

        "but :test/user2 is not"
        (is (not (can? db :test/user2 :admin :test/vpc)))

        "Sanity check that relations don't affect wrong resources"
        (is (not (can? db :test/user2 :view :test/account1)))

        "User 2 can view server 2"
        (is (can? db :test/user2 :view :test/server2))

        "Super User can view all servers"
        (is (can? db :user/super-user :view :test/server1))
        (is (can? db :user/super-user :view :test/server2))

        "User 2 can delete server2 because they have server.owner relation"
        (is (can? db :test/user2 :delete :test/server2))
        "...but not :test/user1"
        (is (not (can? db :test/user1 :delete :test/server2)))

        (testing "We can enumerate subjects that can access a resource."
          ; Bug: currently returns the subject itself which needs a fix.
          (is (= #{(spice-object :user "user-1")
                   ;(spice-object :account "account-1")
                   (spice-object :user "super-user")}
                 (set (lookup-subjects db {:resource     (->server "server-1")
                                           :permission   :view
                                           :subject/type :user}))))

          (testing ":test/user2 is only subject who can delete :test/server2"
            (is (= #{(spice-object :user "user-2")
                     (spice-object :user "super-user")}
                   (set (lookup-subjects db {:resource     (->server "server-2")
                                             :permission   :delete
                                             :subject/type :user}))))))

        (testing "We can enumerate resources with lookup-resources"
          (is (= #{(spice-object :server "server-1")}
                 (set (lookup-resources db {:resource/type :server
                                            :permission    :view
                                            :subject       (->user "user-1")}))))

          (is (= #{(spice-object :account "account-1")}
                 (set (lookup-resources db {:resource/type :account
                                            :permission    :view
                                            :subject       (->user "user-1")}))))

          (is (= #{(spice-object :server "server-2")}
                 (set (lookup-resources db {:resource/type :server
                                            :permission    :view
                                            :subject       (->user "user-2")})))))

        (testing "Make user-1 a shared_admin of server-2"
          (is @(d/transact conn [(Relationship :test/user1 :shared_admin :test/server2)]))) ; this shouldn't be working. no schema for it.

        (let [db (d/db conn)]
          "Now :test/user1 can also :server/delete server 2"
          (is (can? db :test/user1 :delete :test/server2))

          (is (= #{(spice-object :server "server-1")
                   (spice-object :server "server-2")}
                 (set (spiceomic/lookup-resources db {:resource/type :server
                                                      :permission    :view
                                                      :subject       (->user "user-1")}))))
          (is (= #{(spice-object :user "super-user")
                   (spice-object :user "user-1")
                   (spice-object :user "user-2")}
                 (set (spiceomic/lookup-subjects db {:resource     (->server "server-2")
                                                     :permission   :delete
                                                     :subject/type :user})))))

        (testing "Now let's delete all :server/owner Relationships for :test/user2"
          (let [db-for-delete (d/db conn)
                rels          (d/q '[:find [(pull ?rel [* {:eacl/subject [*]}]) ...]
                                     :where
                                     [?rel :eacl.relationship/subject :test/user2]
                                     [?rel :eacl.relationship/relation-name :owner]]
                                   db-for-delete)]
            (is @(d/transact conn (for [rel rels] [:db.fn/retractEntity (:db/id rel)])))))

        (testing "Now only :test/user1 can access both servers."
          (let [db' (d/db conn)]
            (is (= #{
                     ;(spice-object :account "account-2")
                     (spice-object :user "user-1")
                     (spice-object :user "super-user")}
                   (set (lookup-subjects db' {:resource     (->server "server-2")
                                              :permission   :view
                                              :subject/type :user}))))
            (testing ":test/user2 cannot access any servers" ; is this correct?
              (is (= #{}                                    ; Expect empty set of spice objects
                     (set (lookup-resources db' {:resource/type :server
                                                 :permission    :view
                                                 :subject       (->user "user-2")})))))

            (is (not (can? db' :test/user2 :server/delete :test/server2)))

            (testing ":test/user1 permissions remain unchanged"
              (is (= #{(spice-object :server "server-1")
                       (spice-object :server "server-2")}
                     (set (lookup-resources db' {:resource/type :server
                                                 :permission    :view
                                                 :subject       (->user "user-1")}))))

              (is (= #{(spice-object :account "account-1")}
                     (set (lookup-resources db' {:resource/type :account
                                                 :permission    :view
                                                 :subject       (->user "user-1")})))))))

        (testing "pagination: limit & offset are handled correctly for arrow permissions"
          (testing "add a 3rd server. make super-user a direct shared_admin of server1 and server 3 to try and trip up pagination"
            @(d/transact conn [{:db/id         "server3"
                                :db/ident      :test/server3
                                :resource/type :server      ; note, no account.
                                :entity/id     "server-3"}
                               (Relationship :user/super-user :shared_admin :test/server1)
                               (Relationship :user/super-user :shared_admin "server3")]))

          (let [db' (d/db conn)]
            (testing "limit: 10, offset 0 should include all 3 servers"
              (is (= #{(spice-object :server "server-1")
                       (spice-object :server "server-2")
                       (spice-object :server "server-3")}
                     (set (lookup-resources db' {:limit         10
                                                 :offset        0
                                                 :resource/type :server
                                                 :permission    :view
                                                 :subject       (->user "super-user")})))))

            (testing "limit: 10, offset: 1 should exclude server-1"
              (is (= #{(spice-object :server "server-2")
                       (spice-object :server "server-3")}
                     (set (lookup-resources db' {:limit         10
                                                 :offset        1
                                                 :resource/type :server
                                                 :permission    :view
                                                 :subject       (->user "super-user")})))))

            ; Note that return order of Spice resources is not defined, because we do not sort during lookup.
            ; We assume order will be: [server-1, server-3, server-2].
            (testing "limit 1, offset 0 should return first result only, server-1"
              (is (= #{(spice-object :server "server-1")}
                     (set (lookup-resources db' {:offset        0
                                                 :limit         1
                                                 :resource/type :server
                                                 :permission    :view
                                                 :subject       (->user "super-user")})))))

            (testing "limit 1, offset 1 should return 2nd result, server-3"
              (is (= #{(spice-object :server "server-3")}
                     (set (lookup-resources db' {:offset        1
                                                 :limit         1
                                                 :resource/type :server
                                                 :permission    :view
                                                 :subject       (->user "super-user")})))))

            (testing "offset: 2, limit: 10, should return last result only, server-2"
              (is (= #{(spice-object :server "server-2")}
                     (set (lookup-resources db' {:offset        2
                                                 :limit         10
                                                 :resource/type :server
                                                 :permission    :view
                                                 :subject       (->user "super-user")})))))

            (testing "offset: 3, limit: 10 should be empty"
              (is (= #{} (set (lookup-resources db' {:limit         10
                                                     :offset        3
                                                     :resource/type :server
                                                     :permission    :view
                                                     :subject       (->user "super-user")})))))

            (testing "offset: 2, limit: 10 should return last result, server-2"
              (is (= #{(spice-object :server "server-2")}
                     (set (lookup-resources db' {:offset        2
                                                 :limit         10
                                                 :resource/type :server
                                                 :permission    :view
                                                 :subject       (->user "super-user")})))))

            (testing "offset: 2, limit 1, should return last result only, server-2"
              (is (= #{(spice-object :server "server-2")}
                     (set (lookup-resources db' {:limit         1
                                                 :offset        2
                                                 :resource/type :server
                                                 :permission    :view
                                                 :subject       (->user "super-user")})))))))))))